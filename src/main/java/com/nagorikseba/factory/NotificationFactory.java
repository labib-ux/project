package com.nagorikseba.factory;

import com.nagorikseba.entity.Notification;
import com.nagorikseba.entity.User;

public interface NotificationFactory {
    Notification createNotification(User user, String title, String message);
}
