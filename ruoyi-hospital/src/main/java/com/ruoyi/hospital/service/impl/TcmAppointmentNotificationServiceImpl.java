package com.ruoyi.hospital.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.hospital.domain.TcmAppointment;
import com.ruoyi.hospital.domain.TcmBranch;
import com.ruoyi.hospital.domain.TcmClinicSetting;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.domain.TcmRoom;
import com.ruoyi.hospital.domain.TcmServiceType;
import com.ruoyi.hospital.mapper.TcmAppointmentMapper;
import com.ruoyi.hospital.mapper.TcmClinicSettingMapper;
import com.ruoyi.hospital.service.ITcmAppointmentNotificationService;
import com.ruoyi.hospital.service.ITcmBranchService;
import com.ruoyi.hospital.service.ITcmEmailService;
import com.ruoyi.hospital.service.ITcmPatientService;
import com.ruoyi.hospital.service.ITcmRoomService;
import com.ruoyi.hospital.service.ITcmServiceTypeService;
import com.ruoyi.system.mapper.SysUserMapper;

@Service
public class TcmAppointmentNotificationServiceImpl implements ITcmAppointmentNotificationService
{
    private static final Logger log = LoggerFactory.getLogger(TcmAppointmentNotificationServiceImpl.class);
    private static final String DEFAULT_CLINIC_NAME = "OTCM Acupuncture Clinic";
    private static final ZoneId CLINIC_ZONE = ZoneId.of("America/Toronto");
    private java.time.Clock clock = java.time.Clock.system(CLINIC_ZONE);
    private static final DateTimeFormatter MYSQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DISPLAY_DATETIME = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm");
    private static final long REMINDER_HOURS = 24L;
    private static final long FOLLOW_UP_DAYS = 3L;

    private static final String KEY_MANAGE_TOKEN = "manageToken";
    private static final String KEY_CONFIRMATION_SENT_AT = "confirmationEmailSentAt";
    private static final String KEY_REMINDER_SENT_AT = "reminderEmailSentAt";
    private static final String KEY_AFTERCARE_SENT_AT = "aftercareEmailSentAt";
    private static final String KEY_FOLLOW_UP_SENT_AT = "followUpEmailSentAt";
    private static final String KEY_CHANGE_SENT_AT = "changeEmailSentAt";
    private static final String KEY_CANCEL_SENT_AT = "cancelEmailSentAt";
    private static final String KEY_INTERNAL_BOOKING_SENT_AT = "internalBookingEmailSentAt";
    private static final String KEY_TREATMENT_COMPLETED_AT = "treatmentCompletedAt";
    private static final String KEY_NOTIFICATION_OWNER_INSTANCE = "notificationOwnerInstance";
    private static final String KEY_CANCELLATION_SOURCE = "cancellationSource";
    private static final String KEY_CANCELLATION_AT = "cancelledAt";

    private final Object notificationClaimLock = new Object();

    @Autowired
    private TcmAppointmentMapper appointmentMapper;

    @Autowired
    private ITcmPatientService patientService;

    @Autowired
    private ITcmEmailService emailService;

    @Autowired
    private ITcmBranchService branchService;

    @Autowired
    private ITcmRoomService roomService;

    @Autowired
    private ITcmServiceTypeService serviceTypeService;

    @Autowired
    private TcmClinicSettingMapper clinicSettingMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired(required = false)
    @Qualifier("threadPoolTaskExecutor")
    private TaskExecutor notificationTaskExecutor = Runnable::run;

    @Value("${public.app-base-url:${PUBLIC_APP_BASE_URL:http://127.0.0.1:5173}}")
    private String publicAppBaseUrl;

    @Value("${tcm.instance-id:}")
    private String instanceId;

    @Override
    public void handleAppointmentCreated(TcmAppointment appointment)
    {
        if (!isPatientAppointment(appointment))
        {
            return;
        }
        TcmAppointment working = ensureManageToken(appointment);
        sendConfirmationEmail(working);
        sendInternalBookingNotification(working);
    }

    @Override
    public void handleAppointmentUpdated(TcmAppointment before, TcmAppointment after)
    {
        if (!isPatientAppointment(after))
        {
            return;
        }
        TcmAppointment working = ensureManageToken(resetReminderForChangedTime(before, after));
        if (hasMeaningfulChange(before, working))
        {
            sendChangeNotifications(before, working);
        }
    }

    @Override
    public void handleAppointmentStatusChanged(TcmAppointment before, TcmAppointment after)
    {
        if (!isPatientAppointment(after))
        {
            return;
        }
        String previousStatus = normalize(before != null ? before.getStatus() : null);
        String currentStatus = normalize(after.getStatus());

        if ("cancelled".equals(currentStatus) && !"cancelled".equals(previousStatus))
        {
            TcmAppointment working = stampCancellationMeta(after, "system");
            sendCancelNotifications(before, working);
            return;
        }

        if ("confirmed".equals(currentStatus) && !"confirmed".equals(previousStatus))
        {
            TcmAppointment working = ensureManageToken(after);
            sendConfirmationEmail(working);
            return;
        }

        if ("completed".equals(currentStatus) && !"completed".equals(previousStatus))
        {
            TcmAppointment working = stampTreatmentCompletedAt(after, true);
            sendAftercareEmail(working);
        }
    }

    @Override
    public Map<String, Object> getManageInfo(String token)
    {
        TcmAppointment appointment = requireAppointmentByManageToken(token);
        TcmPatient patient = resolvePatient(appointment.getPatientId());
        TcmBranch branch = resolveBranch(appointment.getBranchId());
        SysUser practitioner = resolvePractitioner(appointment.getPractitionerId());
        TcmRoom room = resolveRoom(appointment.getRoomId());
        TcmServiceType serviceType = resolveServiceType(appointment.getServiceType());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appointmentId", appointment.getId());
        result.put("patientName", patient != null ? patient.getName() : "病人");
        result.put("status", appointment.getStatus());
        result.put("serviceLabel", serviceType != null && StringUtils.isNotBlank(serviceType.getLabel())
                ? serviceType.getLabel()
                : appointment.getServiceType());
        result.put("practitionerName", practitioner != null ? practitioner.getNickName() : "");
        result.put("roomName", room != null ? room.getName() : "");
        result.put("startTime", appointment.getStartTime());
        result.put("endTime", appointment.getEndTime());
        result.put("clinicName", resolveClinicName(branch));
        result.put("clinicAddress", resolveClinicAddress(branch));
        result.put("canCancel", canCancelPublicly(appointment));
        return result;
    }

    @Override
    public TcmAppointment cancelByManageToken(String token, String source)
    {
        TcmAppointment appointment = requireAppointmentByManageToken(token);
        return cancelAppointmentInternal(appointment, source);
    }

    @Override
    public TcmAppointment cancelByIntakeToken(String token, String source)
    {
        if (StringUtils.isBlank(token))
        {
            throw new ServiceException("无效的预约令牌");
        }
        TcmAppointment appointment = appointmentMapper.selectTcmAppointmentByIntakeToken(token);
        if (appointment == null)
        {
            throw new ServiceException("预约不存在或链接已失效");
        }
        return cancelAppointmentInternal(appointment, source);
    }

    @Override
    public void processDueNotifications()
    {
        List<TcmAppointment> appointments = appointmentMapper.selectTcmAppointmentList(new TcmAppointment());
        LocalDateTime now = LocalDateTime.now(clock);
        for (TcmAppointment appointment : appointments)
        {
            if (!isPatientAppointment(appointment))
            {
                continue;
            }
            // An imported appointment remains owned by the source site's scheduler.
            String owner = parsePayload(appointment.getPayload()).getString(KEY_NOTIFICATION_OWNER_INSTANCE);
            if (StringUtils.isNotBlank(owner) && !owner.equals(instanceId))
            {
                continue;
            }
            try {
                processReminderIfDue(appointment, now);
                appointment = processScheduledCareIfNeeded(appointment);
                processFollowUpIfDue(appointment, now);
            } catch (Exception error) {
                log.warn("Could not process scheduled notifications for appointment {}", appointment.getId(), error);
            }
        }
    }

    private TcmAppointment processScheduledCareIfNeeded(TcmAppointment appointment)
    {
        if (!isCareStatus(appointment))
        {
            return appointment;
        }
        // The scheduler's list can become stale while a user cancels or reopens treatment.
        TcmAppointment current = appointmentMapper.selectTcmAppointmentById(appointment.getId());
        if (!isCareStatus(current))
        {
            return current != null ? current : appointment;
        }
        appointment = current;
        JSONObject payload = parsePayload(appointment.getPayload());
        if ("completed".equals(normalize(appointment.getStatus())) && StringUtils.isBlank(payload.getString(KEY_TREATMENT_COMPLETED_AT)))
        {
            appointment = stampTreatmentCompletedAt(appointment, false);
            payload = parsePayload(appointment != null ? appointment.getPayload() : null);
        }
        if (StringUtils.isBlank(payload.getString(KEY_AFTERCARE_SENT_AT)))
        {
            sendAftercareEmail(appointment);
            TcmAppointment refreshed = appointmentMapper.selectTcmAppointmentById(appointment.getId());
            return refreshed != null ? refreshed : appointment;
        }
        return appointment;
    }

    private void processReminderIfDue(TcmAppointment appointment, LocalDateTime now)
    {
        String status = normalize(appointment.getStatus());
        if (!"booked".equals(status) && !"confirmed".equals(status))
        {
            return;
        }
        JSONObject payload = parsePayload(appointment.getPayload());
        if (StringUtils.isNotBlank(payload.getString(KEY_REMINDER_SENT_AT)))
        {
            return;
        }
        LocalDateTime start = parseDateTime(appointment.getStartTime());
        if (start == null || !start.isAfter(now))
        {
            return;
        }
        Duration remaining = Duration.between(now, start);
        if (remaining.toHours() > REMINDER_HOURS)
        {
            return;
        }
        sendReminderEmail(appointment);
    }

    private void processFollowUpIfDue(TcmAppointment appointment, LocalDateTime now)
    {
        if (!isCareStatus(appointment))
        {
            return;
        }
        JSONObject payload = parsePayload(appointment.getPayload());
        if (StringUtils.isNotBlank(payload.getString(KEY_FOLLOW_UP_SENT_AT)))
        {
            return;
        }
        LocalDateTime completedAt = treatmentScheduleEnd(appointment);
        if (completedAt == null)
        {
            return;
        }
        if (now.isBefore(completedAt.plusDays(FOLLOW_UP_DAYS)))
        {
            return;
        }
        sendFollowUpEmail(appointment);
    }

    private TcmAppointment cancelAppointmentInternal(TcmAppointment appointment, String source)
    {
        String status = normalize(appointment.getStatus());
        if ("cancelled".equals(status))
        {
            return appointment;
        }
        if (!canCancelPublicly(appointment))
        {
            throw new ServiceException("当前预约不可取消");
        }
        TcmAppointment before = snapshotAppointment(appointment);
        JSONObject payload = parsePayload(appointment.getPayload());
        payload.put(KEY_CANCELLATION_SOURCE, defaultText(source, "patient_public"));
        payload.put(KEY_CANCELLATION_AT, nowString());
        appointment.setStatus("cancelled");
        appointment.setPayload(payload.toJSONString());
        appointmentMapper.updateTcmAppointment(appointment);
        TcmAppointment updated = appointmentMapper.selectTcmAppointmentById(appointment.getId());
        sendCancelNotifications(before, updated);
        return updated;
    }

    private void sendConfirmationEmail(TcmAppointment appointment)
    {
        TcmAppointment working = ensureManageToken(appointment);
        TcmPatient patient = resolvePatient(appointment.getPatientId());
        String toEmail = resolvePrimaryEmail(patient);
        if (patient == null || StringUtils.isBlank(toEmail))
        {
            return;
        }
        working = claimNotification(working, KEY_CONFIRMATION_SENT_AT);
        if (working == null)
        {
            return;
        }
        String consentLink = null;
        String intakeLink = null;

        if (!isConsentSigned(patient))
        {
            String consentToken = patientService.generateConsentToken(patient.getId());
            consentLink = buildConsentLink(consentToken);
        }
        if (!hasPatientIntakeCompleted(patient))
        {
            working = ensureAppointmentIntakeToken(working);
            intakeLink = buildIntakeLink(working.getIntakeToken());
        }

        String manageLink = buildManageLink(extractManageToken(working));
        Map<String, String> variables = buildTemplateVariables(working, patient);
        variables.put("consentLink", defaultText(consentLink, "Already signed — no further action required."));
        variables.put("consentFormLink", variables.get("consentLink"));
        variables.put("intakeLink", defaultText(intakeLink, ""));
        variables.put("manageLink", manageLink);
        variables.put("cancelLink", manageLink);
        addAppointmentSummaryVariables(variables, working, null);
        dispatchTemplateEmail(
                toEmail,
                "appointmentConfirmation",
                variables,
                resolveClinicName(resolveBranch(working.getBranchId())) + "｜预约确认",
                buildConfirmationBody(working, patient, consentLink, intakeLink, manageLink),
                "appointment_confirmation");
    }

    private void sendChangeNotifications(TcmAppointment before, TcmAppointment after)
    {
        after = ensureManageToken(after);
        after = claimNotification(after, KEY_CHANGE_SENT_AT);
        if (after == null)
        {
            return;
        }
        TcmPatient patient = resolvePatient(after.getPatientId());
        String clinicName = resolveClinicName(resolveBranch(after.getBranchId()));
        Map<String, String> variables = buildTemplateVariables(after, patient);
        addAppointmentSummaryVariables(variables, after, before);
        String fallbackSubject = clinicName + "｜预约变动通知";
        String fallbackBody = buildChangeBody(before, after);

        String toEmail = resolvePrimaryEmail(patient);
        if (patient != null && StringUtils.isNotBlank(toEmail))
        {
            dispatchTemplateEmail(toEmail, "appointmentChange", variables, fallbackSubject, fallbackBody, "appointment_change");
        }
        String internalFallbackBody = buildInternalChangeBody(before, after);
        for (String recipient : resolveInternalRecipients(after))
        {
            dispatchTemplateEmail(recipient, "internalAppointmentChange", variables, fallbackSubject, internalFallbackBody, "appointment_change_internal");
        }
    }

    private void sendInternalBookingNotification(TcmAppointment appointment)
    {
        appointment = ensureManageToken(appointment);
        Set<String> recipients = resolveInternalRecipients(appointment);
        if (recipients.isEmpty())
        {
            return;
        }
        appointment = claimNotification(appointment, KEY_INTERNAL_BOOKING_SENT_AT);
        if (appointment == null)
        {
            return;
        }
        String clinicName = resolveClinicName(resolveBranch(appointment.getBranchId()));
        TcmPatient patient = resolvePatient(appointment.getPatientId());
        Map<String, String> variables = buildTemplateVariables(appointment, patient);
        addAppointmentSummaryVariables(variables, appointment, null);
        String fallbackSubject = clinicName + "｜新预约通知";
        String fallbackBody = buildInternalBookingBody(appointment);
        for (String recipient : recipients)
        {
            dispatchTemplateEmail(recipient, "internalBooking", variables, fallbackSubject, fallbackBody, "appointment_internal_new");
        }
    }

    private void sendCancelNotifications(TcmAppointment before, TcmAppointment after)
    {
        after = ensureManageToken(after);
        after = claimNotification(after, KEY_CANCEL_SENT_AT);
        if (after == null)
        {
            return;
        }
        TcmPatient patient = resolvePatient(after.getPatientId());
        String clinicName = resolveClinicName(resolveBranch(after.getBranchId()));
        Map<String, String> variables = buildTemplateVariables(after, patient);
        variables.put("cancelLink", buildManageLink(extractManageToken(after)));
        variables.put("manageLink", variables.get("cancelLink"));
        addAppointmentSummaryVariables(variables, after, before != null ? before : after);
        String fallbackSubject = clinicName + "｜预约取消通知";
        String fallbackBody = buildCancelBody(before != null ? before : after, after);

        String toEmail = resolvePrimaryEmail(patient);
        if (patient != null && StringUtils.isNotBlank(toEmail))
        {
            dispatchTemplateEmail(toEmail, "appointmentCancellation", variables, fallbackSubject, fallbackBody, "appointment_cancel");
        }
        String internalFallbackBody = buildInternalCancelBody(before != null ? before : after, after);
        for (String recipient : resolveInternalRecipients(after))
        {
            dispatchTemplateEmail(recipient, "internalAppointmentCancellation", variables, fallbackSubject, internalFallbackBody, "appointment_cancel_internal");
        }
    }

    private void sendAftercareEmail(TcmAppointment appointment)
    {
        TcmPatient patient = resolvePatient(appointment.getPatientId());
        String toEmail = resolvePrimaryEmail(patient);
        if (patient == null || StringUtils.isBlank(toEmail))
        {
            return;
        }
        appointment = claimScheduledNotification(appointment, KEY_AFTERCARE_SENT_AT);
        if (appointment == null)
        {
            return;
        }
        Map<String, String> variables = buildTemplateVariables(appointment, patient);
        addAppointmentSummaryVariables(variables, appointment, null);
        dispatchScheduledEmail(appointment, KEY_AFTERCARE_SENT_AT,
                toEmail,
                "aftercare",
                variables,
                resolveClinicName(resolveBranch(appointment.getBranchId())) + "｜治疗后护理提醒",
                buildAftercareBody(appointment, patient),
                "appointment_aftercare");
    }

    private void sendReminderEmail(TcmAppointment appointment)
    {
        TcmPatient patient = resolvePatient(appointment.getPatientId());
        String toEmail = resolvePrimaryEmail(patient);
        if (patient == null || StringUtils.isBlank(toEmail))
        {
            return;
        }
        appointment = claimScheduledNotification(appointment, KEY_REMINDER_SENT_AT);
        if (appointment == null)
        {
            return;
        }
        appointment = ensureManageToken(appointment);
        Map<String, String> variables = buildTemplateVariables(appointment, patient);
        addAppointmentSummaryVariables(variables, appointment, null);
        dispatchScheduledEmail(appointment, KEY_REMINDER_SENT_AT,
                toEmail,
                "reminder",
                variables,
                resolveClinicName(resolveBranch(appointment.getBranchId())) + "｜预约提醒",
                buildReminderBody(appointment, patient),
                "appointment_reminder");
    }

    private void sendFollowUpEmail(TcmAppointment appointment)
    {
        TcmPatient patient = resolvePatient(appointment.getPatientId());
        String toEmail = resolvePrimaryEmail(patient);
        if (patient == null || StringUtils.isBlank(toEmail))
        {
            return;
        }
        appointment = claimScheduledNotification(appointment, KEY_FOLLOW_UP_SENT_AT);
        if (appointment == null)
        {
            return;
        }
        Map<String, String> variables = buildTemplateVariables(appointment, patient);
        addAppointmentSummaryVariables(variables, appointment, null);
        dispatchScheduledEmail(appointment, KEY_FOLLOW_UP_SENT_AT,
                toEmail,
                "followUp",
                variables,
                resolveClinicName(resolveBranch(appointment.getBranchId())) + "｜治疗后回访",
                buildFollowUpBody(appointment, patient),
                "appointment_follow_up");
    }

    private void dispatchScheduledEmail(TcmAppointment appointment, String key, String to, String templateKey,
            Map<String, String> variables, String fallbackSubject, String fallbackBody, String type)
    {
        TcmAppointment scheduled = snapshotAppointment(appointment);
        String claim = parsePayload(scheduled.getPayload()).getString(key + "PendingToken");
        Runnable dispatch = () -> {
            try {
                notificationTaskExecutor.execute(() -> {
                    Boolean sent = null;
                    try {
                        TcmAppointment current = appointmentMapper.selectTcmAppointmentById(scheduled.getId());
                        if (!isCurrentScheduledClaim(current, scheduled, key, claim)) return;
                        sent = false;
                        sent = emailService.sendTemplateAndLog(to, templateKey, variables, fallbackSubject, fallbackBody, type);
                    } catch (Exception error) {
                        log.warn("Scheduled appointment email failed: type={}, appointment={}", type, appointment.getId());
                    } finally {
                        finishScheduledNotification(appointment.getId(), key, claim, sent);
                    }
                });
            } catch (RuntimeException error) {
                finishScheduledNotification(appointment.getId(), key, claim, false);
                log.warn("Unable to queue appointment email: appointment={}", appointment.getId());
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch.run(); }
            });
        } else {
            dispatch.run();
        }
    }

    private TcmAppointment claimScheduledNotification(TcmAppointment appointment, String key)
    {
        synchronized (notificationClaimLock) {
            TcmAppointment target = appointmentMapper.selectTcmAppointmentById(appointment.getId());
            if (!isScheduledNotificationEligible(target, key)
                    || !sameScheduledAppointment(appointment, target)) return null;
            JSONObject payload = parsePayload(target.getPayload());
            if (StringUtils.isNotBlank(payload.getString(key))) return null;
            LocalDateTime pending = parseDateTime(payload.getString(key + "PendingAt"));
            if (pending != null && pending.isAfter(LocalDateTime.now(clock).minusMinutes(10))) return null;
            payload.put(key + "PendingAt", nowString());
            payload.put(key + "PendingToken", UUID.randomUUID().toString());
            target.setPayload(payload.toJSONString());
            appointmentMapper.updateTcmAppointment(target);
            return target;
        }
    }

    private boolean isCurrentScheduledClaim(TcmAppointment current, TcmAppointment scheduled, String key, String claim)
    {
        if (!isScheduledNotificationEligible(current, key) || !sameScheduledAppointment(scheduled, current)) return false;
        JSONObject payload = parsePayload(current.getPayload());
        return StringUtils.isBlank(payload.getString(key))
                && Objects.equals(claim, payload.getString(key + "PendingToken"));
    }

    private boolean sameScheduledAppointment(TcmAppointment scheduled, TcmAppointment current)
    {
        return scheduled != null && current != null
                && Objects.equals(scheduled.getPatientId(), current.getPatientId())
                && !hasMeaningfulChange(scheduled, current)
                && Objects.equals(parsePayload(scheduled.getPayload()).getString(KEY_TREATMENT_COMPLETED_AT),
                        parsePayload(current.getPayload()).getString(KEY_TREATMENT_COMPLETED_AT));
    }

    private boolean isScheduledNotificationEligible(TcmAppointment appointment, String key)
    {
        if (!isPatientAppointment(appointment)) return false;
        JSONObject payload = parsePayload(appointment.getPayload());
        String owner = payload.getString(KEY_NOTIFICATION_OWNER_INSTANCE);
        if (StringUtils.isNotBlank(owner) && !owner.equals(instanceId)) return false;
        String status = normalize(appointment.getStatus());
        LocalDateTime now = LocalDateTime.now(clock);
        if (KEY_REMINDER_SENT_AT.equals(key))
        {
            LocalDateTime start = parseDateTime(appointment.getStartTime());
            return ("booked".equals(status) || "confirmed".equals(status)) && start != null
                    && start.isAfter(now) && !start.isAfter(now.plusHours(REMINDER_HOURS));
        }
        if (!isCareStatus(appointment)) return false;
        LocalDateTime completedAt = treatmentScheduleEnd(appointment);
        if (KEY_AFTERCARE_SENT_AT.equals(key)) return completedAt != null && !now.isBefore(completedAt.plusHours(1));
        return KEY_FOLLOW_UP_SENT_AT.equals(key) && completedAt != null
                && !now.isBefore(completedAt.plusDays(FOLLOW_UP_DAYS));
    }

    private TcmAppointment resetReminderForChangedTime(TcmAppointment before, TcmAppointment after)
    {
        if (before == null || Objects.equals(normalize(before.getStartTime()), normalize(after.getStartTime()))) return after;
        synchronized (notificationClaimLock)
        {
            TcmAppointment current = appointmentMapper.selectTcmAppointmentById(after.getId());
            if (current == null) return after;
            if (!Objects.equals(normalize(current.getStartTime()), normalize(after.getStartTime()))) return current;
            JSONObject payload = parsePayload(current.getPayload());
            payload.remove(KEY_REMINDER_SENT_AT);
            payload.remove(KEY_REMINDER_SENT_AT + "PendingAt");
            payload.remove(KEY_REMINDER_SENT_AT + "PendingToken");
            payload.remove(KEY_REMINDER_SENT_AT + "FailedAt");
            current.setPayload(payload.toJSONString());
            appointmentMapper.updateTcmAppointment(current);
            return current;
        }
    }

    private void finishScheduledNotification(String appointmentId, String key, String claim, Boolean sent)
    {
        synchronized (notificationClaimLock) {
            TcmAppointment target = appointmentMapper.selectTcmAppointmentById(appointmentId);
            if (target == null) return;
            JSONObject payload = parsePayload(target.getPayload());
            if (!Objects.equals(claim, payload.getString(key + "PendingToken"))) return;
            payload.remove(key + "PendingAt");
            payload.remove(key + "PendingToken");
            if (Boolean.TRUE.equals(sent)) payload.put(key, nowString());
            else if (Boolean.FALSE.equals(sent)) payload.put(key + "FailedAt", nowString());
            target.setPayload(payload.toJSONString());
            appointmentMapper.updateTcmAppointment(target);
        }
    }

    private void dispatchTemplateEmail(
            String to,
            String templateKey,
            Map<String, String> variables,
            String fallbackSubject,
            String fallbackBody,
            String type)
    {
        if (StringUtils.isBlank(to))
        {
            return;
        }
        notificationTaskExecutor.execute(() -> {
            try
            {
                emailService.sendTemplateAndLog(to, templateKey, variables, fallbackSubject, fallbackBody, type);
            }
            catch (Exception e)
            {
                log.warn("预约模板邮件异步发送失败: type={}, to={}, template={}, error={}", type, to, templateKey, e.getMessage(), e);
            }
        });
    }

    private TcmAppointment ensureManageToken(TcmAppointment appointment)
    {
        JSONObject payload = parsePayload(appointment.getPayload());
        if (StringUtils.isNotBlank(payload.getString(KEY_MANAGE_TOKEN)))
        {
            return appointment;
        }
        payload.put(KEY_MANAGE_TOKEN, UUID.randomUUID().toString().replace("-", ""));
        appointment.setPayload(payload.toJSONString());
        appointmentMapper.updateTcmAppointment(appointment);
        TcmAppointment updated = appointmentMapper.selectTcmAppointmentById(appointment.getId());
        return updated != null ? updated : appointment;
    }

    private TcmAppointment ensureAppointmentIntakeToken(TcmAppointment appointment)
    {
        if (StringUtils.isNotBlank(appointment.getIntakeToken()))
        {
            return appointment;
        }
        appointment.setIntakeToken(UUID.randomUUID().toString().replace("-", ""));
        if (appointment.getIntakeSubmitted() == null)
        {
            appointment.setIntakeSubmitted(0);
        }
        appointmentMapper.updateTcmAppointment(appointment);
        TcmAppointment updated = appointmentMapper.selectTcmAppointmentById(appointment.getId());
        return updated != null ? updated : appointment;
    }

    private TcmAppointment stampCancellationMeta(TcmAppointment appointment, String source)
    {
        JSONObject payload = parsePayload(appointment.getPayload());
        if (StringUtils.isBlank(payload.getString(KEY_CANCELLATION_AT)))
        {
            payload.put(KEY_CANCELLATION_AT, nowString());
        }
        if (StringUtils.isBlank(payload.getString(KEY_CANCELLATION_SOURCE)))
        {
            payload.put(KEY_CANCELLATION_SOURCE, defaultText(source, "system"));
        }
        appointment.setPayload(payload.toJSONString());
        appointmentMapper.updateTcmAppointment(appointment);
        return appointmentMapper.selectTcmAppointmentById(appointment.getId());
    }

    private TcmAppointment stampTreatmentCompletedAt(TcmAppointment appointment, boolean takeNotificationOwnership)
    {
        JSONObject payload = parsePayload(appointment.getPayload());
        boolean changed = false;
        if (StringUtils.isBlank(payload.getString(KEY_TREATMENT_COMPLETED_AT)))
        {
            payload.put(KEY_TREATMENT_COMPLETED_AT, nowString());
            changed = true;
        }
        // Completing treatment here transfers its future care notifications to this instance.
        // Merely scanning imported history must not take ownership or send old notifications.
        if (takeNotificationOwnership)
        {
            if (StringUtils.isBlank(instanceId))
            {
                changed |= payload.containsKey(KEY_NOTIFICATION_OWNER_INSTANCE);
                payload.remove(KEY_NOTIFICATION_OWNER_INSTANCE);
            }
            else if (!instanceId.equals(payload.getString(KEY_NOTIFICATION_OWNER_INSTANCE)))
            {
                payload.put(KEY_NOTIFICATION_OWNER_INSTANCE, instanceId);
                changed = true;
            }
        }
        if (changed)
        {
            appointment.setPayload(payload.toJSONString());
            appointmentMapper.updateTcmAppointment(appointment);
            return appointmentMapper.selectTcmAppointmentById(appointment.getId());
        }
        return appointment;
    }

    private TcmAppointment claimNotification(TcmAppointment appointment, String key)
    {
        if (appointment == null || StringUtils.isBlank(appointment.getId()) || StringUtils.isBlank(key))
        {
            return null;
        }
        synchronized (notificationClaimLock)
        {
            TcmAppointment latest = appointmentMapper.selectTcmAppointmentById(appointment.getId());
            TcmAppointment target = latest != null ? latest : appointment;
            JSONObject payload = parsePayload(target.getPayload());
            if (StringUtils.isNotBlank(payload.getString(key)))
            {
                return null;
            }
            payload.put(key, nowString());
            target.setPayload(payload.toJSONString());
            appointmentMapper.updateTcmAppointment(target);
            TcmAppointment updated = appointmentMapper.selectTcmAppointmentById(target.getId());
            return updated != null ? updated : target;
        }
    }

    private boolean hasMeaningfulChange(TcmAppointment before, TcmAppointment after)
    {
        if (before == null || after == null)
        {
            return false;
        }
        return !Objects.equals(normalize(before.getStartTime()), normalize(after.getStartTime()))
                || !Objects.equals(normalize(before.getEndTime()), normalize(after.getEndTime()))
                || !Objects.equals(normalize(before.getPractitionerId()), normalize(after.getPractitionerId()))
                || !Objects.equals(normalize(before.getRoomId()), normalize(after.getRoomId()))
                || !Objects.equals(normalize(before.getServiceType()), normalize(after.getServiceType()))
                || !Objects.equals(normalize(before.getBranchId()), normalize(after.getBranchId()));
    }

    private boolean isPatientAppointment(TcmAppointment appointment)
    {
        return appointment != null
                && StringUtils.isNotBlank(appointment.getId())
                && StringUtils.isNotBlank(appointment.getPatientId())
                && !"time_block".equals(normalize(appointment.getServiceType()));
    }

    private boolean canCancelPublicly(TcmAppointment appointment)
    {
        if (appointment == null)
        {
            return false;
        }
        String status = normalize(appointment.getStatus());
        if ("cancelled".equals(status) || "completed".equals(status))
        {
            return false;
        }
        LocalDateTime start = parseDateTime(appointment.getStartTime());
        return start == null || start.isAfter(LocalDateTime.now(clock));
    }

    private TcmAppointment requireAppointmentByManageToken(String token)
    {
        if (StringUtils.isBlank(token))
        {
            throw new ServiceException("无效的预约令牌");
        }
        TcmAppointment managedAppointment = appointmentMapper.selectTcmAppointmentByManageToken(token);
        if (managedAppointment != null) return managedAppointment;
        TcmAppointment intakeAppointment = appointmentMapper.selectTcmAppointmentByIntakeToken(token);
        if (intakeAppointment != null)
        {
            return ensureManageToken(intakeAppointment);
        }
        throw new ServiceException("预约不存在或链接已失效");
    }

    private Set<String> resolveInternalRecipients(TcmAppointment appointment)
    {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        TcmBranch branch = resolveBranch(appointment.getBranchId());
        if (branch != null && StringUtils.isNotBlank(branch.getEmail()))
        {
            recipients.add(branch.getEmail().trim());
        }
        SysUser practitioner = resolvePractitioner(appointment.getPractitionerId());
        if (practitioner != null && StringUtils.isNotBlank(practitioner.getEmail()))
        {
            recipients.add(practitioner.getEmail().trim());
        }
        String clinicEmail = getSettingValue("clinicEmail");
        if (StringUtils.isNotBlank(clinicEmail))
        {
            recipients.add(clinicEmail.trim());
        }
        return recipients;
    }

    private String buildConfirmationBody(
            TcmAppointment appointment,
            TcmPatient patient,
            String consentLink,
            String intakeLink,
            String manageLink)
    {
        String clinicName = resolveClinicName(resolveBranch(appointment.getBranchId()));
        StringBuilder body = new StringBuilder();
        body.append("您好，").append(defaultText(patient != null ? patient.getName() : null, "病人")).append("：\n\n");
        body.append("您的预约已经确认，详情如下：\n");
        body.append("诊所：").append(clinicName).append("\n");
        body.append("地址：").append(resolveClinicAddress(resolveBranch(appointment.getBranchId()))).append("\n");
        body.append("时间：").append(formatDisplayDateTime(appointment.getStartTime())).append("\n");
        body.append("服务：").append(resolveServiceLabel(appointment)).append("\n");
        body.append("医生：").append(resolvePractitionerName(appointment.getPractitionerId())).append("\n");
        body.append("诊室：").append(resolveRoomName(appointment.getRoomId())).append("\n\n");
        body.append("到诊注意事项：\n");
        body.append("1. 请提前10分钟到达。\n");
        body.append("2. 如近期身体状况有变化，请及时联系诊所。\n");
        body.append("3. 如不能前来，请尽快通过下方链接取消预约。\n\n");
        if (StringUtils.isNotBlank(consentLink))
        {
            body.append("首次来诊请先阅读并签署知情同意书：\n");
            body.append(consentLink).append("\n\n");
        }
        if (StringUtils.isNotBlank(intakeLink))
        {
            body.append("首次来诊请先填写就诊资料表：\n");
            body.append(intakeLink).append("\n\n");
        }
        body.append("如需取消预约，请点击：\n");
        body.append(manageLink).append("\n\n");
        body.append("如有疑问，请直接联系诊所。\n");
        body.append(clinicName);
        return body.toString();
    }

    private Map<String, String> buildTemplateVariables(TcmAppointment appointment, TcmPatient patient)
    {
        Map<String, String> variables = new LinkedHashMap<>();
        TcmBranch branch = resolveBranch(appointment != null ? appointment.getBranchId() : null);
        variables.put("clinicName", resolveClinicName(branch));
        variables.put("clinicAddress", resolveClinicAddress(branch));
        variables.put("patientId", patient != null ? defaultText(patient.getId(), "") : "");
        variables.put("patientName", defaultText(resolvePatientFirstName(patient), "Patient"));
        variables.put("patientEmail", resolvePrimaryEmail(patient));
        variables.put("patientPhone", defaultText(patient != null ? patient.getPhone() : null, ""));
        variables.put("appointmentDate", formatDisplayDate(appointment != null ? appointment.getStartTime() : null));
        variables.put("appointmentTime", formatDisplayTime(appointment != null ? appointment.getStartTime() : null));
        variables.put("appointmentStartTime", defaultText(appointment != null ? appointment.getStartTime() : null, ""));
        variables.put("appointmentEndTime", defaultText(appointment != null ? appointment.getEndTime() : null, ""));
        variables.put("serviceLabel", resolveServiceLabel(appointment));
        variables.put("servicePrice", resolveServicePrice(appointment));
        variables.put("practitionerName", appointment != null ? resolvePractitionerName(appointment.getPractitionerId()) : "");
        variables.put("roomName", appointment != null ? resolveRoomName(appointment.getRoomId()) : "");
        variables.put("manageLink", appointment != null ? buildManageLink(extractManageToken(appointment)) : "");
        variables.put("cancelLink", variables.get("manageLink"));
        variables.put("consentLink", "");
        variables.put("intakeLink", "");
        return variables;
    }

    private String resolvePrimaryEmail(TcmPatient patient)
    {
        if (patient == null)
        {
            return "";
        }
        String primary = patient.getEmail() != null ? patient.getEmail().trim() : "";
        if (StringUtils.isNotBlank(primary))
        {
            return primary;
        }
        JSONObject payload = parsePayload(patient.getPayload());
        Object emails = payload.get("emails");
        if (emails instanceof List<?>)
        {
            for (Object value : (List<?>) emails)
            {
                String email = value != null ? String.valueOf(value).trim() : "";
                if (StringUtils.isNotBlank(email))
                {
                    return email;
                }
            }
        }
        return "";
    }

    private String resolvePatientFirstName(TcmPatient patient)
    {
        if (patient == null)
        {
            return "";
        }
        String firstName = patient.getFirstName() != null ? patient.getFirstName().trim() : "";
        if (StringUtils.isNotBlank(firstName))
        {
            return firstName;
        }
        JSONObject payload = parsePayload(patient.getPayload());
        firstName = payload.getString("firstName");
        if (StringUtils.isNotBlank(firstName))
        {
            return firstName.trim();
        }
        String name = patient.getName() != null ? patient.getName().trim() : "";
        if (StringUtils.isBlank(name))
        {
            return "";
        }
        String[] parts = name.split("\\s+");
        if (parts.length >= 2)
        {
            return parts[parts.length - 1];
        }
        return name;
    }

    private String resolveServicePrice(TcmAppointment appointment)
    {
        if (appointment == null || StringUtils.isBlank(appointment.getServiceType()))
        {
            return "";
        }
        TcmServiceType serviceType = serviceTypeService.selectByKey(appointment.getServiceType());
        BigDecimal price = serviceType != null ? serviceType.getDefaultPrice() : null;
        if (price == null)
        {
            return "";
        }
        return "CAD " + price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void addAppointmentSummaryVariables(
            Map<String, String> variables,
            TcmAppointment appointment,
            TcmAppointment previousAppointment)
    {
        String currentSummary = buildBriefSummary(appointment).trim();
        String previousSummary = buildBriefSummary(previousAppointment != null ? previousAppointment : appointment).trim();
        variables.put("appointmentSummary", currentSummary);
        variables.put("latestAppointmentSummary", currentSummary);
        variables.put("previousAppointmentSummary", previousSummary);
        JSONObject payload = parsePayload(appointment != null ? appointment.getPayload() : null);
        variables.put("cancellationSource", defaultText(payload.getString(KEY_CANCELLATION_SOURCE), "system"));
    }

    private String formatDisplayDate(String value)
    {
        LocalDateTime parsed = parseDateTime(value);
        return parsed != null ? parsed.toLocalDate().toString() : defaultText(value, "");
    }

    private String formatDisplayTime(String value)
    {
        LocalDateTime parsed = parseDateTime(value);
        return parsed != null ? parsed.toLocalTime().toString() : "";
    }

    private String buildConsentFormBody(TcmAppointment appointment, TcmPatient patient, String consentLink)
    {
        String clinicName = resolveClinicName(resolveBranch(appointment.getBranchId()));
        return "您好，" + defaultText(patient != null ? patient.getName() : null, "病人") + "：\n\n"
                + "请在就诊前阅读并签署诊疗同意书：\n"
                + consentLink + "\n\n"
                + "预约时间：" + formatDisplayDateTime(appointment.getStartTime()) + "\n"
                + "诊所：" + clinicName + "\n\n"
                + clinicName;
    }

    private String buildIntakeFormBody(TcmAppointment appointment, TcmPatient patient, String intakeLink)
    {
        String clinicName = resolveClinicName(resolveBranch(appointment.getBranchId()));
        return "您好，" + defaultText(patient != null ? patient.getName() : null, "病人") + "：\n\n"
                + "请在就诊前填写首诊文件：\n"
                + intakeLink + "\n\n"
                + "预约时间：" + formatDisplayDateTime(appointment.getStartTime()) + "\n"
                + "诊所：" + clinicName + "\n\n"
                + clinicName;
    }

    private String buildReminderBody(TcmAppointment appointment, TcmPatient patient)
    {
        String clinicName = resolveClinicName(resolveBranch(appointment.getBranchId()));
        return "您好，" + defaultText(patient != null ? patient.getName() : null, "病人") + "：\n\n"
                + "提醒您，您在 " + clinicName + " 的预约即将到来。\n"
                + "预约时间：" + formatDisplayDateTime(appointment.getStartTime()) + "\n"
                + "诊所地址：" + resolveClinicAddress(resolveBranch(appointment.getBranchId())) + "\n"
                + "医生：" + resolvePractitionerName(appointment.getPractitionerId()) + "\n"
                + "请提前10分钟到达，如需取消，请使用确认邮件中的取消链接。\n\n"
                + clinicName;
    }

    private String buildAftercareBody(TcmAppointment appointment, TcmPatient patient)
    {
        String clinicName = resolveClinicName(resolveBranch(appointment.getBranchId()));
        return "您好，" + defaultText(patient != null ? patient.getName() : null, "病人") + "：\n\n"
                + "感谢您今天到 " + clinicName + " 接受治疗。为帮助身体恢复，请留意以下护理建议：\n"
                + "1. 今天请注意保暖，避免受凉、淋雨和二次损伤。\n"
                + "2. 针灸后 4 小时内尽量不要洗澡；24 小时内避免饮酒、熬夜和剧烈运动。\n"
                + "3. 如治疗部位出现轻微酸胀、发热或瘀痕，通常属于正常反应，请先休息观察。\n"
                + "4. 如果今天或这几天出现明显疼痛、肿胀、持续出血、头晕或其他异常，请尽快联系诊所。\n"
                + "诊所地址：" + resolveClinicAddress(resolveBranch(appointment.getBranchId())) + "\n\n"
                + clinicName;
    }

    private String buildFollowUpBody(TcmAppointment appointment, TcmPatient patient)
    {
        String clinicName = resolveClinicName(resolveBranch(appointment.getBranchId()));
        return "您好，" + defaultText(patient != null ? patient.getName() : null, "病人") + "：\n\n"
                + "这是治疗后的回访邮件，想了解您这几天的恢复情况。\n"
                + "如果恢复顺利，请继续按护理建议休息和观察；如果仍有不适或出现异常，请及时联系诊所。\n"
                + "感谢您对 " + clinicName + " 的信任。\n\n"
                + clinicName;
    }

    private String buildChangeBody(TcmAppointment before, TcmAppointment after)
    {
        String clinicName = resolveClinicName(resolveBranch(after.getBranchId()));
        return "您好：\n\n"
                + "您的预约信息已有变动，请以最新安排为准。\n\n"
                + "原安排：\n"
                + buildBriefSummary(before)
                + "\n最新安排：\n"
                + buildBriefSummary(after)
                + "\n如新时间不方便，请联系诊所，或使用以下链接取消预约：\n"
                + buildManageLink(extractManageToken(after)) + "\n\n"
                + clinicName;
    }

    private String buildInternalChangeBody(TcmAppointment before, TcmAppointment after)
    {
        TcmPatient patient = resolvePatient(after.getPatientId());
        return "预约变动通知\n\n"
                + "病人：" + defaultText(patient != null ? patient.getName() : null, after.getPatientId()) + "\n"
                + "原安排：\n" + buildBriefSummary(before)
                + "\n新安排：\n" + buildBriefSummary(after);
    }

    private String buildCancelBody(TcmAppointment before, TcmAppointment after)
    {
        String clinicName = resolveClinicName(resolveBranch(after.getBranchId()));
        return "您好：\n\n"
                + "您的预约已取消。\n\n"
                + "取消的预约信息：\n"
                + buildBriefSummary(before)
                + "\n如需重新预约，请联系诊所。\n\n"
                + clinicName;
    }

    private String buildInternalBookingBody(TcmAppointment appointment)
    {
        TcmPatient patient = resolvePatient(appointment.getPatientId());
        return "新预约通知\n\n"
                + "病人：" + defaultText(patient != null ? patient.getName() : null, appointment.getPatientId()) + "\n"
                + "预约信息：\n"
                + buildBriefSummary(appointment)
                + "\n管理链接：" + buildManageLink(extractManageToken(appointment));
    }

    private String buildInternalCancelBody(TcmAppointment before, TcmAppointment after)
    {
        TcmPatient patient = resolvePatient(after.getPatientId());
        JSONObject payload = parsePayload(after.getPayload());
        return "预约取消通知\n\n"
                + "病人：" + defaultText(patient != null ? patient.getName() : null, after.getPatientId()) + "\n"
                + "预约信息：\n"
                + buildBriefSummary(before)
                + "\n取消来源：" + defaultText(payload.getString(KEY_CANCELLATION_SOURCE), "system");
    }

    private String buildBriefSummary(TcmAppointment appointment)
    {
        if (appointment == null)
        {
            return "-\n";
        }
        if (appointment != null)
        {
            return "Time: " + formatDisplayDateTime(appointment.getStartTime()) + "\n"
                    + "Service: " + resolveServiceLabel(appointment) + "\n"
                    + "Practitioner: " + resolvePractitionerName(appointment.getPractitionerId()) + "\n"
                    + "Room: " + resolveRoomName(appointment.getRoomId()) + "\n"
                    + "Address: " + resolveClinicAddress(resolveBranch(appointment.getBranchId())) + "\n";
        }
        return "时间：" + formatDisplayDateTime(appointment.getStartTime()) + "\n"
                + "服务：" + resolveServiceLabel(appointment) + "\n"
                + "医生：" + resolvePractitionerName(appointment.getPractitionerId()) + "\n"
                + "诊室：" + resolveRoomName(appointment.getRoomId()) + "\n"
                + "地址：" + resolveClinicAddress(resolveBranch(appointment.getBranchId())) + "\n";
    }

    private String buildManageLink(String token)
    {
        if (StringUtils.isBlank(token))
        {
            return "";
        }
        return normalizePublicBaseUrl() + "manage/" + token;
    }

    private String buildConsentLink(String token)
    {
        if (StringUtils.isBlank(token))
        {
            return "";
        }
        return normalizePublicBaseUrl() + "consent/" + token;
    }

    private String buildIntakeLink(String token)
    {
        if (StringUtils.isBlank(token))
        {
            return "";
        }
        return normalizePublicBaseUrl() + "intake/" + token;
    }

    private String extractManageToken(TcmAppointment appointment)
    {
        return parsePayload(appointment.getPayload()).getString(KEY_MANAGE_TOKEN);
    }

    private boolean isConsentSigned(TcmPatient patient)
    {
        return patient != null && patient.getConsentSigned() != null && patient.getConsentSigned() == 1;
    }

    private boolean hasPatientIntakeCompleted(TcmPatient patient)
    {
        if (patient == null)
        {
            return false;
        }
        JSONObject payload = parsePayload(patient.getPayload());
        if (payload.getBooleanValue("latestIntakeCompleted"))
        {
            return true;
        }
        String latestIntakeSource = normalize(payload.getString("latestIntakeSource"));
        if ("public_intake_form".equals(latestIntakeSource)
                || "patient_intake_form".equals(latestIntakeSource)
                || "appointment_intake_form".equals(latestIntakeSource))
        {
            return true;
        }
        Object latestIntake = payload.get("latestIntakeFormData");
        if (latestIntake instanceof Map<?, ?>)
        {
            Map<?, ?> intake = (Map<?, ?>) latestIntake;
            return Boolean.TRUE.equals(intake.get("completed"));
        }
        if (latestIntake instanceof JSONObject)
        {
            return ((JSONObject) latestIntake).getBooleanValue("completed");
        }
        return false;
    }

    private String resolveClinicName(TcmBranch branch)
    {
        if (branch != null && StringUtils.isNotBlank(branch.getName()))
        {
            return branch.getName().trim();
        }
        return normalizeClinicName(getSettingValue("clinicName"));
    }

    private String normalizeClinicName(String value)
    {
        String text = value != null ? value.trim() : "";
        if (StringUtils.isBlank(text)
                || "TCM Clinic".equalsIgnoreCase(text)
                || "TCM Clinic Management System".equalsIgnoreCase(text)
                || "\u8bca\u6240".equals(text))
        {
            return DEFAULT_CLINIC_NAME;
        }
        return text;
    }

    private String resolveClinicAddress(TcmBranch branch)
    {
        if (branch != null && StringUtils.isNotBlank(branch.getAddress()))
        {
            return branch.getAddress().trim();
        }
        return defaultText(getSettingValue("clinicAddress"), "请联系诊所确认地址");
    }

    private String resolveServiceLabel(TcmAppointment appointment)
    {
        TcmServiceType serviceType = resolveServiceType(appointment != null ? appointment.getServiceType() : null);
        if (serviceType != null && StringUtils.isNotBlank(serviceType.getLabel()))
        {
            return serviceType.getLabel().trim();
        }
        return defaultText(appointment != null ? appointment.getServiceType() : null, "-");
    }

    private String resolvePractitionerName(String practitionerId)
    {
        SysUser practitioner = resolvePractitioner(practitionerId);
        return practitioner != null ? defaultText(practitioner.getNickName(), practitionerId) : defaultText(practitionerId, "-");
    }

    private String resolveRoomName(String roomId)
    {
        TcmRoom room = resolveRoom(roomId);
        return room != null ? defaultText(room.getName(), roomId) : defaultText(roomId, "-");
    }

    private TcmPatient resolvePatient(String patientId)
    {
        if (StringUtils.isBlank(patientId))
        {
            return null;
        }
        try
        {
            return patientService.selectTcmPatientById(patientId);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private TcmBranch resolveBranch(String branchId)
    {
        if (StringUtils.isBlank(branchId))
        {
            return null;
        }
        try
        {
            return branchService.selectTcmBranchById(branchId);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private TcmRoom resolveRoom(String roomId)
    {
        if (StringUtils.isBlank(roomId))
        {
            return null;
        }
        try
        {
            return roomService.selectTcmRoomById(roomId);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private TcmServiceType resolveServiceType(String serviceTypeKey)
    {
        if (StringUtils.isBlank(serviceTypeKey))
        {
            return null;
        }
        try
        {
            return serviceTypeService.selectByKey(serviceTypeKey);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private SysUser resolvePractitioner(String practitionerId)
    {
        if (StringUtils.isBlank(practitionerId))
        {
            return null;
        }
        try
        {
            return userMapper.selectUserById(Long.valueOf(practitionerId));
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private String getSettingValue(String key)
    {
        try
        {
            TcmClinicSetting setting = clinicSettingMapper.selectSettingByKey(key);
            return setting != null ? setting.getSettingValue() : "";
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private TcmAppointment snapshotAppointment(TcmAppointment appointment)
    {
        if (appointment == null)
        {
            return null;
        }
        TcmAppointment snapshot = new TcmAppointment();
        snapshot.setId(appointment.getId());
        snapshot.setPatientId(appointment.getPatientId());
        snapshot.setPractitionerId(appointment.getPractitionerId());
        snapshot.setRoomId(appointment.getRoomId());
        snapshot.setServiceType(appointment.getServiceType());
        snapshot.setStartTime(appointment.getStartTime());
        snapshot.setEndTime(appointment.getEndTime());
        snapshot.setStatus(appointment.getStatus());
        snapshot.setBranchId(appointment.getBranchId());
        snapshot.setIntakeToken(appointment.getIntakeToken());
        snapshot.setIntakeSubmitted(appointment.getIntakeSubmitted());
        snapshot.setPayload(appointment.getPayload());
        snapshot.setCreateTime(appointment.getCreateTime());
        snapshot.setUpdateTime(appointment.getUpdateTime());
        return snapshot;
    }

    private JSONObject parsePayload(String payload)
    {
        if (StringUtils.isBlank(payload))
        {
            return new JSONObject();
        }
        try
        {
            JSONObject parsed = JSON.parseObject(payload);
            return parsed != null ? parsed : new JSONObject();
        }
        catch (Exception e)
        {
            return new JSONObject();
        }
    }

    private LocalDateTime parseDateTime(String value)
    {
        if (StringUtils.isBlank(value))
        {
            return null;
        }
        try
        {
            return LocalDateTime.parse(value.trim(), MYSQL_DATETIME);
        }
        catch (Exception e)
        {
            try { return java.time.OffsetDateTime.parse(value.trim()).atZoneSameInstant(CLINIC_ZONE).toLocalDateTime(); }
            catch (Exception ignored) {
                try { return LocalDateTime.parse(value.trim()); }
                catch (Exception invalid) { return null; }
            }
        }
    }

    private LocalDateTime treatmentScheduleEnd(TcmAppointment appointment)
    {
        LocalDateTime end = parseDateTime(appointment.getEndTime());
        return end != null ? end : parseDateTime(parsePayload(appointment.getPayload()).getString(KEY_TREATMENT_COMPLETED_AT));
    }

    private boolean isCareStatus(TcmAppointment appointment)
    {
        if (appointment == null) return false;
        String status = normalize(appointment.getStatus());
        return "completed".equals(status) || "confirmed".equals(status) || "booked".equals(status);
    }

    private String formatDisplayDateTime(String value)
    {
        LocalDateTime dateTime = parseDateTime(value);
        return dateTime != null ? dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : defaultText(value, "-");
    }

    private String nowString()
    {
        return LocalDateTime.now(clock).format(MYSQL_DATETIME);
    }

    private String normalizePublicBaseUrl()
    {
        String baseUrl = StringUtils.defaultIfBlank(publicAppBaseUrl, "http://127.0.0.1:5173").trim();
        if (!baseUrl.endsWith("/"))
        {
            baseUrl += "/";
        }
        return baseUrl;
    }

    private String normalize(String value)
    {
        return value == null ? "" : value.trim();
    }

    private String defaultText(String value, String fallback)
    {
        return StringUtils.isNotBlank(value) ? value.trim() : fallback;
    }
}
