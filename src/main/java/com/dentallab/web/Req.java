package com.dentallab.web;

import com.dentallab.config.BizException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 请求体(Map)解析工具。 */
public final class Req {

    private Req() {
    }

    public static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    public static String strReq(Map<String, Object> body, String key, String label) {
        String v = str(body, key);
        if (v == null || v.isEmpty()) {
            throw new BizException(label + "(" + key + ")不能为空");
        }
        return v;
    }

    public static Integer intVal(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BizException(key + " 必须是整数");
        }
    }

    public static int intReq(Map<String, Object> body, String key, String label) {
        Integer v = intVal(body, key);
        if (v == null) {
            throw new BizException(label + "(" + key + ")不能为空");
        }
        return v;
    }

    public static Long longVal(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BizException(key + " 必须是整数");
        }
    }

    public static Long longReq(Map<String, Object> body, String key, String label) {
        Long v = longVal(body, key);
        if (v == null) {
            throw new BizException(label + "(" + key + ")不能为空");
        }
        return v;
    }

    public static BigDecimal decimal(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BizException(key + " 必须是数字");
        }
    }

    public static boolean bool(Map<String, Object> body, String key, boolean dft) {
        Object v = body.get(key);
        if (v == null) {
            return dft;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    /** 解析布尔值；字段缺省返回 null（用于必须显式确认的 true/false 入参）。 */
    public static Boolean boolVal(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        throw new BizException(key + " 必须是 true 或 false");
    }

    public static LocalDate date(Map<String, Object> body, String key) {
        String v = str(body, key);
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (Exception e) {
            throw new BizException(key + " 日期格式应为 yyyy-MM-dd");
        }
    }

    public static LocalDateTime dateTime(Map<String, Object> body, String key) {
        String v = str(body, key);
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            String norm = v.length() == 16 ? v + ":00" : v;
            return LocalDateTime.parse(norm.replace(' ', 'T'));
        } catch (Exception e) {
            throw new BizException(key + " 时间格式应为 yyyy-MM-ddTHH:mm:ss");
        }
    }

    /** 解析字符串列表：支持 JSON 数组或英文逗号分隔字符串。 */
    @SuppressWarnings("unchecked")
    public static List<String> strList(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return new ArrayList<>();
        }
        if (v instanceof List<?> list) {
            return list.stream().map(o -> String.valueOf(o).trim())
                    .filter(s -> !s.isEmpty()).collect(Collectors.toList());
        }
        return Arrays.stream(String.valueOf(v).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    public static <E extends Enum<E>> E enumVal(Map<String, Object> body, String key, Class<E> type) {
        String v = str(body, key);
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return Enum.valueOf(type, v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(key + " 取值非法: " + v + "，可选: " + Arrays.toString(type.getEnumConstants()));
        }
    }

    public static <E extends Enum<E>> E enumReq(Map<String, Object> body, String key, Class<E> type, String label) {
        E v = enumVal(body, key, type);
        if (v == null) {
            throw new BizException(label + "(" + key + ")不能为空");
        }
        return v;
    }
}
