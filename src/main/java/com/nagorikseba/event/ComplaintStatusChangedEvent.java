package com.nagorikseba.event;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.enums.ComplaintStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class ComplaintStatusChangedEvent extends ApplicationEvent {
    private final Complaint complaint;
    private final ComplaintStatus oldStatus;
    private final ComplaintStatus newStatus;
    private final User actor;
    private final String note;

    public ComplaintStatusChangedEvent(Object source, Complaint complaint, ComplaintStatus oldStatus, ComplaintStatus newStatus, User actor, String note) {
        super(source);
        this.complaint = complaint;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.actor = actor;
        this.note = note;
    }
}
