package com.nagorikseba.factory;

import com.nagorikseba.entity.Notification;
import com.nagorikseba.entity.User;
import com.nagorikseba.enums.NotificationChannel;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationFactory implements NotificationFactory {
    @Override
    public Notification createNotification(User user, String title, String message) {
        return Notification.builder()
                .user(user)
                .title(title)
                .message(message)
                .channel(NotificationChannel.EMAIL)
                .build();
    }
}
