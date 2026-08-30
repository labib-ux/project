package com.nagorikseba.observer;

import com.nagorikseba.entity.Notification;
import com.nagorikseba.event.ComplaintStatusChangedEvent;
import com.nagorikseba.factory.NotificationFactory;
import com.nagorikseba.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationObserver {
    private final NotificationFactory emailNotificationFactory;
    private final NotificationRepository notificationRepository;

    @EventListener
    public void onComplaintStatusChanged(ComplaintStatusChangedEvent event) {
        String title = "Complaint Update: " + event.getComplaint().getTitle();
        String message = String.format("Your complaint status changed from %s to %s. Note: %s", 
                event.getOldStatus(), event.getNewStatus(), event.getNote());
        
        Notification notification = emailNotificationFactory.createNotification(
                event.getComplaint().getCitizen(), title, message);
        
        notificationRepository.save(notification);
        log.info("Simulating sending email to {}: {}", event.getComplaint().getCitizen().getEmail(), message);
    }
}
