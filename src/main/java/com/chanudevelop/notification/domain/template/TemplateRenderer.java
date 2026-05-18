package com.chanudevelop.notification.domain.template;

import com.chanudevelop.notification.domain.NotificationTemplate;
import com.chanudevelop.notification.domain.dispatcher.RenderedMessage;
import com.chanudevelop.notification.domain.template.exception.TemplateRenderException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 템플릿 + payload를 합쳐 최종 메시지(title, body)를 만드는 도메인 서비스.
 *
 * <p>워커가 PENDING 알림을 처리할 때 사용한다.
 * <ul>
 *   <li>입력: {@link NotificationTemplate} (titleTemplate, bodyTemplate) + payload(Map)</li>
 *   <li>출력: {@link RenderedMessage} (title, body)</li>
 * </ul>
 *
 * <p>문법: {@code {{key}}} (Mustache 스타일 단순 치환).
 * 예: {@code "{{userName}}님, 환영합니다"} + {@code {"userName": "김철수"}}
 *     → {@code "김철수님, 환영합니다"}
 *
 * <p>치환 후에도 {@code {{...}}} 패턴이 남으면 {@link TemplateRenderException}을 던진다.
 * (Fail Fast: 잘못된 데이터를 조용히 발송하지 않음.)
 */
@Component
public class TemplateRenderer {

    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\{\\{[^}]+}}", Pattern.DOTALL);

    public RenderedMessage render(NotificationTemplate template, Map<String, Object> payload) {
        String title = substitute(template.getTitleTemplate(), payload);
        String body = substitute(template.getBodyTemplate(), payload);
        return new RenderedMessage(title, body);
    }

    private String substitute(String template, Map<String, Object> payload) {
        String result = template;
        if (payload != null) {
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                String placeholder = "{{" + entry.getKey() + "}}";
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                result = result.replace(placeholder, value);
            }
        }
        if (UNRESOLVED_PLACEHOLDER.matcher(result).find()) {
            throw new TemplateRenderException("Unresolved template placeholder in: " + result);
        }
        return result;
    }
}
