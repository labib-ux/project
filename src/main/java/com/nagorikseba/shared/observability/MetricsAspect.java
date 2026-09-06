package com.nagorikseba.shared.observability;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.lifecycle.TransitionCommand;
import com.nagorikseba.shared.outbox.OutboxMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Micrometer meters without touching the measured beans (§10).
 *
 * <p>All counters here are recorded from an aspect so submit/transition/scan/
 * dispatch flows need no instrumentation edits of their own:
 * <ul>
 *   <li>{@code complaint.submitted} — every template submission return</li>
 *   <li>{@code complaint.transition{action,status}} — every lifecycle
 *   {@code execute()} return (status = resulting status) plus SUBMIT records</li>
 *   <li>{@code sla.breach.detected} — scanner passes, incremented by findings</li>
 *   <li>{@code notification.delivery{channel,result}} — dispatcher returns and
 *   throws (channel derived from the event type)</li>
 * </ul>
 * The same aspect carries per-transition MDC ({@code complaintId},
 * {@code municipalityId}) around {@code execute()}: the filter owns
 * {@code traceId}, the service call owns the tenant log fields.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class MetricsAspect {

    private final MeterRegistry meterRegistry;

    @AfterReturning("execution(* com.nagorikseba.complaint.submission.ComplaintSubmissionTemplate.submit(..))")
    public void countSubmission() {
        Counter.builder("complaint.submitted")
                .description("Complaints submitted")
                .register(meterRegistry)
                .increment();
    }

    @AfterReturning(
            pointcut = "execution(* com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService.execute(..))"
                    + " && args(command)",
            returning = "complaint",
            argNames = "command,complaint")
    public void countTransition(TransitionCommand command, Complaint complaint) {
        Counter.builder("complaint.transition")
                .description("Complaint lifecycle transitions")
                .tag("action", command.action().name())
                .tag("status", complaint.getStatus().name())
                .register(meterRegistry)
                .increment();
    }

    @AfterReturning(
            pointcut = "execution(* com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService.recordSubmission(..))",
            returning = "transition")
    public void countSubmissionAudit(Object transition) {
        Counter.builder("complaint.transition")
                .description("Complaint lifecycle transitions")
                .tag("action", "SUBMIT")
                .tag("status", "SUBMITTED")
                .register(meterRegistry)
                .increment();
    }

    @AfterReturning(
            pointcut = "execution(* com.nagorikseba.sla.SlaBreachScanner.scanOnce(..))",
            returning = "detected",
            argNames = "detected")
    public void countBreaches(int detected) {
        if (detected > 0) {
            Counter.builder("sla.breach.detected")
                    .description("SLA breaches detected by the scanner")
                    .register(meterRegistry)
                    .increment(detected);
        }
    }

    @AfterReturning(
            pointcut = "execution(* com.nagorikseba.notification.NotificationDispatcher.dispatch(..))"
                    + " && args(message)",
            argNames = "message")
    public void countDelivery(OutboxMessage message) {
        Counter.builder("notification.delivery")
                .description("Outbox deliveries by the dispatcher")
                .tag("channel", channelOf(message.getEventType()))
                .tag("result", "sent")
                .register(meterRegistry)
                .increment();
    }

    @AfterThrowing(
            pointcut = "execution(* com.nagorikseba.notification.NotificationDispatcher.dispatch(..))"
                    + " && args(message)",
            argNames = "message")
    public void countDeliveryFailure(OutboxMessage message) {
        Counter.builder("notification.delivery")
                .description("Outbox deliveries by the dispatcher")
                .tag("channel", channelOf(message.getEventType()))
                .tag("result", "failed")
                .register(meterRegistry)
                .increment();
    }

    /** Tenant log enrichment around every lifecycle transition (best effort). */
    @Around("execution(* com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService.execute(..))")
    public Object enrichTransitionMdc(ProceedingJoinPoint joinPoint) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            if (result instanceof Complaint complaint) {
                MDC.put("complaintId", String.valueOf(complaint.getId()));
                if (complaint.getMunicipality() != null) {
                    MDC.put("municipalityId", String.valueOf(complaint.getMunicipality().getId()));
                }
            }
        } catch (Exception ignored) {
            // Logging enrichment must never break the transition itself.
        }
        return result;
    }

    private static String channelOf(String eventType) {
        if (eventType.startsWith("SMS")) {
            return "SMS";
        }
        if (eventType.startsWith("EMAIL")) {
            return "EMAIL";
        }
        return "IN_APP";
    }
}
