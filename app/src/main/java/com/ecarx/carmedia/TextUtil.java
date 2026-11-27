package com.ecarx.carmedia;

/**
 * 文本归一化工具（歌名/歌手归属校验用）。
 * <p>
 * 统一原 CloudLyricFetcher.norm() 和 CarMediaService.normalizeTitle() 两份实现。
 * 采用 norm() 的激进归一化策略（去除分隔符和括号），因为归属校验越宽松越不会误杀歌词。
 */
public final class TextUtil {

    private TextUtil() {}

 /**
 * 归一化：全半角括号统一后去除括号及括号内内容、去空白、分隔符去除、忽略大小写。
 * 用于歌词归属校验（歌名/歌手比较），防止全半角括号、分隔符等格式差异导致误杀。
 * <p>
 * 修复：括号内容必须一并去除（如"后来 (电视剧《真情》片尾曲)" → "后来"、
 * "海阔天空(Live)" → "海阔天空"）。原实现只删括号字符、保留括号内内容，导致歌名带括号时
 * 与网易云搜索返回的无括号原版/翻唱版归属校验失配，歌词永远拿不到。
 */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        // 全半角括号统一为半角
        String t = s.replace('（', '(').replace('）', ')')
                .replace('【', '[').replace('】', ']')
                .replace('〔', '[').replace('〕', ']');
        // 反复去除括号及括号内内容(支持嵌套)，如"后来 (电视剧《真情》片尾曲)" → "后来"
        String prev;
        do {
            prev = t;
            t = prev.replaceAll("\\([^()]*\\)", "")
                    .replaceAll("\\[[^\\[\\]]*\\]", "");
        } while (!t.equals(prev));
        // 常见分隔符统一处理，防止"刀马旦" vs "刀马旦·电影版" 等误杀
        return t.replaceAll("\\s+", "")
                .replace("·", "").replace("•", "").replace("⋅", "")
                .replace("：", ":").replace(":", "")
                .replace("～", "~").replace("~", "")
                .replace("－", "-").replace("—", "-").replace("–", "-").replace("-", "")
                .toLowerCase();
    }
}
