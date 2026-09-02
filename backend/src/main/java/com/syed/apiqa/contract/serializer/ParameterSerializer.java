package com.syed.apiqa.contract.serializer;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

/**
 * Universal Parameter Serializer implementing OpenAPI 3.x parameter styles:
 * path (simple, matrix, label) and query (form, spaceDelimited, pipeDelimited, deepObject).
 */
@Component
public class ParameterSerializer {

    public String serializePathParameter(String paramName, Object value, String style, boolean explode) {
        if (value == null) return "";
        String sStyle = style != null ? style.toLowerCase() : "simple";

        return switch (sStyle) {
            case "matrix" -> {
                if (value instanceof Collection<?> col) {
                    if (explode) {
                        StringBuilder sb = new StringBuilder();
                        for (Object o : col) {
                            sb.append(";").append(paramName).append("=").append(encode(o));
                        }
                        yield sb.toString();
                    } else {
                        yield ";" + paramName + "=" + joinWith(col, ",");
                    }
                }
                yield ";" + paramName + "=" + encode(value);
            }
            case "label" -> {
                if (value instanceof Collection<?> col) {
                    yield "." + joinWith(col, explode ? "." : ",");
                }
                yield "." + encode(value);
            }
            default -> { // "simple"
                if (value instanceof Collection<?> col) {
                    yield joinWith(col, ",");
                }
                yield encode(value);
            }
        };
    }

    public String serializeQueryParameter(String paramName, Object value, String style, boolean explode) {
        if (value == null) return "";
        String sStyle = style != null ? style.toLowerCase() : "form";

        return switch (sStyle) {
            case "spacedelimited" -> {
                if (value instanceof Collection<?> col) {
                    yield paramName + "=" + joinWith(col, "%20");
                }
                yield paramName + "=" + encode(value);
            }
            case "pipedelimited" -> {
                if (value instanceof Collection<?> col) {
                    yield paramName + "=" + joinWith(col, "|");
                }
                yield paramName + "=" + encode(value);
            }
            case "deepobject" -> {
                if (value instanceof Map<?, ?> map) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (sb.length() > 0) sb.append("&");
                        sb.append(paramName).append("[").append(encode(entry.getKey())).append("]=").append(encode(entry.getValue()));
                    }
                    yield sb.toString();
                }
                yield paramName + "=" + encode(value);
            }
            default -> { // "form"
                if (value instanceof Collection<?> col) {
                    if (explode) {
                        StringBuilder sb = new StringBuilder();
                        for (Object o : col) {
                            if (sb.length() > 0) sb.append("&");
                            sb.append(paramName).append("=").append(encode(o));
                        }
                        yield sb.toString();
                    } else {
                        yield paramName + "=" + joinWith(col, ",");
                    }
                }
                yield paramName + "=" + encode(value);
            }
        };
    }

    private String encode(Object obj) {
        return obj != null ? URLEncoder.encode(String.valueOf(obj), StandardCharsets.UTF_8) : "";
    }

    private String joinWith(Collection<?> col, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (Object item : col) {
            if (sb.length() > 0) sb.append(delimiter);
            sb.append(encode(item));
        }
        return sb.toString();
    }
}
