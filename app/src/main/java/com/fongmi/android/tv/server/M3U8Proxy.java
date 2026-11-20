package com.fongmi.android.tv.server;

import android.net.Uri;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Rule;
import com.github.catvod.utils.Util;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在交给 IJK 前改写 HLS 播放列表，并按多特征评分过滤插播广告。 */
public final class M3U8Proxy {

    private static final String EXTM3U = "#EXTM3U";
    private static final String EXTINF = "#EXTINF";
    private static final String DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String CUE_OUT = "#EXT-X-CUE-OUT";
    private static final String CUE_OUT_CONT = "#EXT-X-CUE-OUT-CONT";
    private static final String CUE_IN = "#EXT-X-CUE-IN";
    private static final String DATERANGE = "#EXT-X-DATERANGE";
    private static final String SCTE35 = "SCTE35";

    // 中文备注：达到 60 分才删除，避免只凭一次域名或目录变化误删正片。
    private static final int DROP_SCORE = 60;
    private static final int SCORE_EXPLICIT_CUE = 100;
    private static final int SCORE_RULE = 100;
    private static final int SCORE_DISCONTINUITY = 40;
    private static final int SCORE_PATH_ANOMALY = 35;
    private static final int SCORE_DOMAIN_ANOMALY = 25;
    private static final int SCORE_SHORT_GROUP = 15;
    private static final int SCORE_RECOVERY = 30;

    private static final int MAX_SHORT_GROUP_SEGMENTS = 15;
    private static final double DOMINANT_THRESHOLD = 0.80d;

    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern DURATION = Pattern.compile("#EXTINF:([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern AD_DATERANGE = Pattern.compile(
            "(?i)(CLASS|ID)=\"[^\"]*(ad|advert|commercial|interstitial|promo)[^\"]*\"|SCTE35");
    private static final String TOKEN = java.util.UUID.randomUUID().toString();

    private M3U8Proxy() {
    }

    public static String getToken() {
        return TOKEN;
    }

    public static boolean validToken(String value) {
        return TOKEN.equals(value);
    }

    public static boolean isPlaylistUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        String path = Uri.parse(value).getPath();
        if (path == null) return lower.contains("m3u8");
        return path.toLowerCase(Locale.ROOT).endsWith(".m3u8") || lower.contains("m3u8?");
    }

    public static String rewrite(String sourceUrl, String content, Map<String, String> headers) {
        if (content == null || !content.trim().startsWith(EXTM3U)) return content;

        List<String> resolved = new ArrayList<>();
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            resolved.add(resolveLine(sourceUrl, line, headers));
        }

        // 中文备注：主列表只代理子 m3u8，不把清晰度地址误判成广告分片。
        if (isMasterPlaylist(resolved)) return join(resolved);

        Playlist playlist = parse(resolved);
        if (playlist.segments.isEmpty()) return join(resolved);

        List<Rule> rules = matchingRules(sourceUrl);
        Stats stats = Stats.create(playlist.segments);
        markRuleMatches(playlist.segments, rules);
        scoreGroups(playlist.groups, stats);
        return render(playlist);
    }

    private static boolean isMasterPlaylist(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
                return true;
            }
        }
        return false;
    }

    private static Playlist parse(List<String> lines) {
        Playlist playlist = new Playlist();
        List<String> pending = new ArrayList<>();
        Group group = new Group();
        boolean cueAd = false;
        boolean oneShotExplicit = false;
        boolean boundaryBeforeNext = false;

        for (String line : lines) {
            if (line.startsWith(CUE_OUT) || line.startsWith(CUE_OUT_CONT)) {
                // 中文备注：CUE-OUT 前先结束正片组，避免把前面的正片连同广告一起删除。
                if (!group.segments.isEmpty()) {
                    group.boundaryAfter = true;
                    playlist.groups.add(group);
                    group = new Group();
                }
                cueAd = true;
                boundaryBeforeNext = true;
                pending.clear();
                pending.add(line);
                continue;
            }
            if (line.startsWith(CUE_IN)) {
                // 中文备注：CUE-IN 结束广告组，后面的分片重新建立正片组。
                if (!group.segments.isEmpty()) {
                    group.boundaryAfter = true;
                    playlist.groups.add(group);
                    group = new Group();
                }
                cueAd = false;
                oneShotExplicit = false;
                boundaryBeforeNext = true;
                pending.clear();
                continue;
            }
            if (line.startsWith(DATERANGE) && (AD_DATERANGE.matcher(line).find() || line.toUpperCase(Locale.ROOT).contains(SCTE35))) {
                // 中文备注：DATERANGE 不一定有结束标签，只给紧随其后的候选段加明确广告分，避免误删后续全部正片。
                oneShotExplicit = true;
                pending.add(line);
                continue;
            }
            if (line.startsWith(DISCONTINUITY)) {
                if (!group.segments.isEmpty()) {
                    group.boundaryAfter = true;
                    playlist.groups.add(group);
                    group = new Group();
                }
                boundaryBeforeNext = true;
                pending.add(line);
                continue;
            }

            if (isSegmentUri(line)) {
                Segment segment = new Segment();
                segment.lines.addAll(pending);
                segment.lines.add(line);
                segment.uri = line.trim();
                segment.host = normalizeHost(segment.uri);
                segment.directory = directory(segment.uri);
                segment.duration = duration(segment.lines);
                segment.explicitAd = cueAd || oneShotExplicit || containsExplicitAdSignal(segment.lines);
                oneShotExplicit = false;
                segment.boundaryBefore = boundaryBeforeNext;
                boundaryBeforeNext = false;
                pending.clear();

                if (group.segments.isEmpty()) group.boundaryBefore = segment.boundaryBefore;
                group.segments.add(segment);
                playlist.segments.add(segment);
                continue;
            }

            if (isSegmentMetadata(line) || !pending.isEmpty()) {
                pending.add(line);
            } else {
                playlist.header.add(line);
            }
        }

        if (!group.segments.isEmpty()) playlist.groups.add(group);
        playlist.tail.addAll(pending);
        return playlist;
    }

    private static boolean isSegmentUri(String line) {
        return line != null && !line.isEmpty() && !line.startsWith("#");
    }

    private static boolean isSegmentMetadata(String line) {
        return line.startsWith(EXTINF)
                || line.startsWith("#EXT-X-BYTERANGE")
                || line.startsWith("#EXT-X-GAP")
                || line.startsWith("#EXT-X-PROGRAM-DATE-TIME")
                || line.startsWith("#EXT-X-KEY")
                || line.startsWith("#EXT-X-MAP")
                || line.startsWith(DATERANGE)
                || line.startsWith(CUE_OUT)
                || line.startsWith(CUE_OUT_CONT)
                || line.startsWith(CUE_IN)
                || line.startsWith(DISCONTINUITY);
    }

    private static boolean containsExplicitAdSignal(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith(CUE_OUT) || line.startsWith(CUE_OUT_CONT)) return true;
            if (line.startsWith(DATERANGE) && AD_DATERANGE.matcher(line).find()) return true;
        }
        return false;
    }

    private static void markRuleMatches(List<Segment> segments, List<Rule> rules) {
        for (Segment segment : segments) {
            String block = join(segment.lines);
            for (Rule rule : rules) {
                if (rule == null || rule.getRegex() == null) continue;
                for (String expression : rule.getRegex()) {
                    if (expression == null || expression.trim().isEmpty()) continue;
                    try {
                        if (Pattern.compile(expression, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE)
                                .matcher(block).find()) {
                            segment.ruleMatch = true;
                            break;
                        }
                    } catch (RuntimeException ignored) {
                        // 中文备注：忽略无效正则，避免整条播放列表无法播放。
                    }
                }
                if (segment.ruleMatch) break;
            }
        }
    }

    private static void scoreGroups(List<Group> groups, Stats stats) {
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            boolean explicit = false;
            boolean ruleMatch = false;
            boolean pathAnomaly = !group.segments.isEmpty();
            boolean domainAnomaly = !group.segments.isEmpty();

            for (Segment segment : group.segments) {
                explicit |= segment.explicitAd;
                ruleMatch |= segment.ruleMatch;
                pathAnomaly &= stats.dominantDirectory != null
                        && !stats.dominantDirectory.equals(segment.directory);
                domainAnomaly &= stats.dominantHost != null
                        && segment.host != null
                        && !stats.dominantHost.equalsIgnoreCase(segment.host);
            }

            if (explicit) group.score += SCORE_EXPLICIT_CUE;
            if (ruleMatch) group.score += SCORE_RULE;
            if (group.boundaryBefore && group.boundaryAfter) group.score += SCORE_DISCONTINUITY;
            if (pathAnomaly) group.score += SCORE_PATH_ANOMALY;
            if (domainAnomaly) group.score += SCORE_DOMAIN_ANOMALY;
            if (group.segments.size() <= MAX_SHORT_GROUP_SEGMENTS) group.score += SCORE_SHORT_GROUP;

            // 中文备注：候选块前后都恢复到相同主目录/主域名时，广告置信度明显提高。
            if (isRecoveryPattern(groups, i, stats)) group.score += SCORE_RECOVERY;

            group.drop = group.score >= DROP_SCORE;
        }
    }

    private static boolean isRecoveryPattern(List<Group> groups, int index, Stats stats) {
        if (index <= 0 || index >= groups.size() - 1) return false;
        Group previous = groups.get(index - 1);
        Group next = groups.get(index + 1);
        if (previous.segments.isEmpty() || next.segments.isEmpty()) return false;

        Segment left = previous.segments.get(previous.segments.size() - 1);
        Segment right = next.segments.get(0);
        boolean sameDirectory = left.directory != null && left.directory.equals(right.directory);
        boolean sameHost = left.host != null && left.host.equalsIgnoreCase(right.host);
        boolean returnsToDominant = (stats.dominantDirectory != null && stats.dominantDirectory.equals(left.directory))
                || (stats.dominantHost != null && stats.dominantHost.equalsIgnoreCase(left.host));
        return (sameDirectory || sameHost) && returnsToDominant;
    }

    private static String render(Playlist playlist) {
        List<String> output = new ArrayList<>(playlist.header);
        boolean removed = false;

        for (Group group : playlist.groups) {
            if (group.drop) {
                removed = true;
                continue;
            }
            if (removed && (output.isEmpty() || !output.get(output.size() - 1).startsWith(DISCONTINUITY))) {
                // 中文备注：广告删除后的正片连接处保留一个时间轴切换标记，降低 IJK 卡顿概率。
                output.add(DISCONTINUITY);
            }
            for (Segment segment : group.segments) output.addAll(segment.lines);
            removed = false;
        }

        output.addAll(playlist.tail);
        return join(removeDuplicateDiscontinuity(output));
    }

    private static List<String> removeDuplicateDiscontinuity(List<String> lines) {
        List<String> result = new ArrayList<>();
        boolean previous = false;
        for (String line : lines) {
            boolean current = line.startsWith(DISCONTINUITY);
            if (current && previous) continue;
            result.add(line);
            if (!line.isEmpty()) previous = current;
        }
        return result;
    }

    private static double duration(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = DURATION.matcher(line);
            if (matcher.find()) {
                try {
                    return Double.parseDouble(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return 0d;
                }
            }
        }
        return 0d;
    }

    private static String normalizeHost(String value) {
        String host = Uri.parse(value).getHost();
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    private static String directory(String value) {
        String path = Uri.parse(value).getPath();
        if (path == null) path = value;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash + 1) : path;
    }

    private static String resolveLine(String base, String line, Map<String, String> headers) {
        if (line == null || line.isEmpty()) return line;
        if (!line.startsWith("#")) {
            String absolute = resolve(base, line.trim());
            return isPlaylistUrl(absolute) ? localUrl(absolute, headers) : absolute;
        }

        Matcher matcher = URI_ATTRIBUTE.matcher(line);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String absolute = resolve(base, matcher.group(1));
            String replacement = isPlaylistUrl(absolute) ? localUrl(absolute, headers) : absolute;
            matcher.appendReplacement(result, Matcher.quoteReplacement("URI=\"" + replacement + "\""));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String localUrl(String sourceUrl, Map<String, String> headers) {
        try {
            return Server.get().getAddress("/m3u8?token=" + TOKEN
                    + "&url=" + URLEncoder.encode(sourceUrl, "UTF-8")
                    + "&headers=" + URLEncoder.encode(App.gson().toJson(headers), "UTF-8"));
        } catch (Exception e) {
            return sourceUrl;
        }
    }

    private static String resolve(String base, String reference) {
        try {
            return new URI(base).resolve(new URI(reference)).toString();
        } catch (Exception e) {
            return reference;
        }
    }

    private static List<Rule> matchingRules(String sourceUrl) {
        String host = Uri.parse(sourceUrl).getHost();
        List<Rule> result = new ArrayList<>();
        if (host == null) return result;
        addRules(result, VodConfig.get().getRules(), host);
        addRules(result, LiveConfig.get().getRules(), host);
        return result;
    }

    private static void addRules(List<Rule> result, List<Rule> candidates, String host) {
        if (candidates == null) return;
        for (Rule rule : candidates) {
            if (rule == null || rule.getHosts() == null) continue;
            for (String candidate : rule.getHosts()) {
                if (candidate == null || candidate.isEmpty()) continue;
                try {
                    if (Util.containOrMatch(host, candidate)) {
                        result.add(rule);
                        break;
                    }
                } catch (Throwable ignored) {
                    if (host.contains(candidate)) {
                        result.add(rule);
                        break;
                    }
                }
            }
        }
    }

    private static String join(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) result.append(line).append('\n');
        return result.toString();
    }

    private static final class Playlist {
        final List<String> header = new ArrayList<>();
        final List<String> tail = new ArrayList<>();
        final List<Segment> segments = new ArrayList<>();
        final List<Group> groups = new ArrayList<>();
    }

    private static final class Segment {
        final List<String> lines = new ArrayList<>();
        String uri;
        String host;
        String directory;
        double duration;
        boolean explicitAd;
        boolean ruleMatch;
        boolean boundaryBefore;
    }

    private static final class Group {
        final List<Segment> segments = new ArrayList<>();
        boolean boundaryBefore;
        boolean boundaryAfter;
        int score;
        boolean drop;
    }

    private static final class Stats {
        String dominantDirectory;
        String dominantHost;

        static Stats create(List<Segment> segments) {
            Stats stats = new Stats();
            Map<String, Integer> directories = new LinkedHashMap<>();
            Map<String, Integer> hosts = new LinkedHashMap<>();
            for (Segment segment : segments) {
                increment(directories, segment.directory);
                increment(hosts, segment.host);
            }
            stats.dominantDirectory = dominant(directories, segments.size());
            stats.dominantHost = dominant(hosts, segments.size());
            return stats;
        }

        private static void increment(Map<String, Integer> values, String key) {
            if (key == null || key.isEmpty()) return;
            Integer count = values.get(key);
            values.put(key, count == null ? 1 : count + 1);
        }

        private static String dominant(Map<String, Integer> values, int total) {
            String best = null;
            int max = 0;
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                if (entry.getValue() > max) {
                    best = entry.getKey();
                    max = entry.getValue();
                }
            }
            return total > 0 && max * 1.0d / total >= DOMINANT_THRESHOLD ? best : null;
        }
    }
}
