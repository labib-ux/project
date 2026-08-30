package com.nagorikseba.observer;

import com.nagorikseba.entity.StatusUpdate;
import com.nagorikseba.event.ComplaintStatusChangedEvent;
import com.nagorikseba.repository.StatusUpdateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AuditLogObserver {
    private final StatusUpdateRepository statusUpdateRepository;

    @EventListener
    @Transactional
    public void onComplaintStatusChanged(ComplaintStatusChangedEvent event) {
        statusUpdateRepository.save(StatusUpdate.builder()
                .complaint(event.getComplaint())
                .updatedBy(event.getActor())
                .fromStatus(event.getOldStatus())
                .toStatus(event.getNewStatus())
                .note(event.getNote())
                .build());
    }
}
