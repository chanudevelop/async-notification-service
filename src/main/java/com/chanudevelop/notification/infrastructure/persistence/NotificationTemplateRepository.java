package com.chanudevelop.notification.infrastructure.persistence;

import com.chanudevelop.notification.domain.NotificationChannel;
import com.chanudevelop.notification.domain.NotificationTemplate;
import com.chanudevelop.notification.domain.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    Optional<NotificationTemplate> findFirstByTypeAndLocaleAndChannelAndActiveTrue(
            NotificationType type, String locale, NotificationChannel channel
    );

    Optional<NotificationTemplate> findFirstByTypeAndLocaleAndChannelIsNullAndActiveTrue(
            NotificationType type, String locale
    );
}
