package com.nagorikseba.state;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.entity.Department;
import com.nagorikseba.entity.User;
import com.nagorikseba.enums.ComplaintStatus;
import com.nagorikseba.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ComplaintStateMachine {

    private final List<ComplaintState> statesList;
    private final ComplaintRepository complaintRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    private Map<ComplaintStatus, ComplaintState> statesMap;

    @jakarta.annotation.PostConstruct
    public void init() {
        statesMap = statesList.stream()
                .collect(Collectors.toMap(ComplaintState::getStatusName, Function.identity()));
    }

    @Transactional
    public Complaint process(Complaint complaint, ComplaintAction action, User actor, String note,
            Department assignedDept, Integer rating) {
        ComplaintStatus oldStatus = complaint.getStatus();
        ComplaintState currentState = statesMap.get(oldStatus);
        if (currentState == null) {
            throw new IllegalStateException("Unknown state for status: " + oldStatus);
        }

        switch (action) {
            case VERIFY -> currentState.verify(complaint, actor, note);
            case ASSIGN -> currentState.assign(complaint, assignedDept, actor, note);
            case START_WORK -> currentState.startWork(complaint, actor, note);
            case RESOLVE -> currentState.resolve(complaint, actor, note);
            case CLOSE -> currentState.close(complaint, actor, rating, note);
            case REOPEN -> currentState.reopen(complaint, actor, note);
        }

        Complaint updatedComplaint = complaintRepository.saveAndFlush(complaint);

        eventPublisher.publishEvent(new com.nagorikseba.event.ComplaintStatusChangedEvent(
                this, updatedComplaint, oldStatus, updatedComplaint.getStatus(), actor, note));

        return updatedComplaint;
    }
}
