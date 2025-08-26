package al;

import java.util.HashMap;
import java.util.Map;

public class _3 {
    /**
     * 滑动窗口
     */
    public static int lengthOfLongestSubstring(String s) {
        int result = 0;
        int len = s.length();
        int point = 0;
        Map<Character, Integer> map = new HashMap<>();
        while (point < len) {
            char c = s.charAt(point);
            if (!map.containsKey(c)) {
                map.put(c, point);
                point++;
                continue;
            }
            result = Math.max(map.size(), result);
            point = map.get(c) + 1;
            map.clear();

        }
        result = Math.max(map.size(), result);
        return result;
    }

}
