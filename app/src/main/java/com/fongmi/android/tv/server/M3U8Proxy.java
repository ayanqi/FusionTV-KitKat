package com.fongmi.android.tv.server;

import android.net.Uri;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Rule;
import com.github.catvod.utils.Util;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Playlist-level HLS ad filtering, executed before IJK receives the media source. */
public final class M3U8Proxy {

    private static final String EXTINF = "#EXTINF";
    private static final String DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String CUE_OUT = "#EXT-X-CUE-OUT";
    private static final String CUE_OUT_CONT = "#EXT-X-CUE-OUT-CONT";
    private static final String CUE_IN = "#EXT-X-CUE-IN";
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");
    private static final String TOKEN = java.util.UUID.randomUUID().toString();

    private M3U8Proxy() {}

    public static String getToken() {
        return TOKEN;
    }

    public static boolean isPlaylistUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        String path = Uri.parse(value).getPath();
        if (path == null) return lower.contains("m3u8");
        return path.toLowerCase(Locale.ROOT).endsWith(".m3u8") || lower.contains("m3u8?");
    }

    public static boolean validToken(String value) {
        return TOKEN.equals(value);
    }

    public static String rewrite(String sourceUrl, String content, Map<String, String> headers) {
        if (content == null || !content.trim().startsWith("#EXTM3U")) return content;

        List<String> lines = new ArrayList<>();
        for (String line : content.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            lines.add(resolveLine(sourceUrl, line, headers));
        }

        // 中文备注：主播放列表只改写子 M3U8 地址，不把清晰度列表误当成媒体分片过滤。
        if (isMasterPlaylist(lines)) return join(lines);

        return join(filterSegments(lines, matchingRules(sourceUrl)));
    }

    private static boolean isMasterPlaylist(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) return true;
        }
        return false;
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
                    + "&headers=" + URLEncoder.encode(com.fongmi.android.tv.App.gson().toJson(headers), "UTF-8"));
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

    private static List<String> filterSegments(List<String> lines, List<Rule> rules) {
        Map<String, Integer> prefixes = new HashMap<>();
        Map<String, Integer> domains = new HashMap<>();
        int segmentCount = 0;

        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            segmentCount++;
            String path = Uri.parse(line).getPath();
            if (path == null) path = line;
            int slash = path.lastIndexOf('/');
            String directory = slash >= 0 ? path.substring(0, slash + 1) : path;
            prefixes.put(directory, prefixes.containsKey(directory) ? prefixes.get(directory) + 1 : 1);

            String host = Uri.parse(line).getHost();
            if (host != null) domains.put(host, domains.containsKey(host) ? domains.get(host) + 1 : 1);
        }

        String dominantPrefix = dominant(prefixes, segmentCount, 0.80);
        String dominantDomain = dominant(domains, segmentCount, 0.80);
        boolean domainMode = dominantPrefix == null && dominantDomain != null && domains.size() > 1;

        List<String> output = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        boolean cueAd = false;

        for (String line : lines) {
            // 中文备注：明确的 HLS 广告提示标签，从 CUE-OUT 开始丢弃，到 CUE-IN 恢复。
            if (line.startsWith(CUE_OUT) || line.startsWith(CUE_OUT_CONT)) {
                cueAd = true;
                pending.clear();
                continue;
            }
            if (line.startsWith(CUE_IN)) {
                cueAd = false;
                pending.clear();
                // 需要保留时间轴切换标记，避免 IJK 在广告切除点卡住。
                output.add(DISCONTINUITY);
                continue;
            }

            if (!line.startsWith("#") && !line.isEmpty()) {
                pending.add(line);
                boolean drop = cueAd || shouldDrop(pending, rules, dominantPrefix, dominantDomain, domainMode, domains);
                if (!drop) output.addAll(pending);
                pending.clear();
                continue;
            }

            if (line.startsWith(EXTINF)
                    || line.startsWith("#EXT-X-BYTERANGE")
                    || line.startsWith("#EXT-X-GAP")
                    || line.startsWith("#EXT-X-PROGRAM-DATE-TIME")) {
                pending.add(line);
            } else if (line.startsWith(DISCONTINUITY)) {
                // 中文备注：先暂存 DISCONTINUITY；若下一个分片被判定为广告，会连同标记一起删除。
                pending.add(line);
            } else if (cueAd) {
                // 广告区块中的 KEY、MAP、DATERANGE 等辅助标签不输出。
            } else if (pending.isEmpty()) {
                output.add(line);
            } else {
                pending.add(line);
            }
        }

        if (!cueAd) output.addAll(pending);
        return removeDuplicateDiscontinuity(output);
    }

    private static boolean shouldDrop(List<String> entry, List<Rule> rules, String dominantPrefix,
                                      String dominantDomain, boolean domainMode,
                                      Map<String, Integer> domains) {
        String segment = entry.get(entry.size() - 1);
        String path = Uri.parse(segment).getPath();
        if (path == null) path = segment;

        // 中文备注：只有主目录占比达 80% 时，才把小众目录当作插播段，降低误删风险。
        if (dominantPrefix != null && !path.startsWith(dominantPrefix)) return true;

        String host = Uri.parse(segment).getHost();
        if (domainMode && host != null && !host.equalsIgnoreCase(dominantDomain)) {
            Integer count = domains.get(host);
            if (count != null && count <= 15) return true;
        }

        String block = join(entry);
        for (Rule rule : rules) {
            if (rule == null || rule.getRegex() == null) continue;
            for (String expression : rule.getRegex()) {
                if (expression == null || expression.trim().isEmpty()) continue;
                try {
                    // 中文备注：规则直接匹配完整 EXTINF + URI 块，支持路径、时长和跨行正则。
                    if (Pattern.compile(expression, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE)
                            .matcher(block).find()) return true;
                } catch (RuntimeException ignored) {
                    // 无效正则跳过，避免整条 M3U8 播放失败。
                }
            }
        }
        return false;
    }

    private static List<String> removeDuplicateDiscontinuity(List<String> lines) {
        List<String> result = new ArrayList<>();
        boolean lastDiscontinuity = false;
        for (String line : lines) {
            boolean current = line.startsWith(DISCONTINUITY);
            if (current && lastDiscontinuity) continue;
            result.add(line);
            if (!line.isEmpty()) lastDiscontinuity = current;
        }
        return result;
    }

    private static String dominant(Map<String, Integer> counts, int total, double threshold) {
        String best = null;
        int max = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > max) {
                best = entry.getKey();
                max = entry.getValue();
            }
        }
        return total > 0 && max * 1.0 / total >= threshold && counts.size() > 1 ? best : null;
    }

    private static String join(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) result.append(line).append('\n');
        return result.toString();
    }
}
