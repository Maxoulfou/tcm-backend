package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.hospital.domain.TcmAppointment;
import com.ruoyi.hospital.domain.TcmBranch;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.domain.TcmRoom;
import com.ruoyi.hospital.domain.TcmServiceType;
import com.ruoyi.hospital.mapper.TcmAppointmentMapper;
import com.ruoyi.hospital.mapper.TcmClinicSettingMapper;
import com.ruoyi.hospital.service.ITcmBranchService;
import com.ruoyi.hospital.service.ITcmEmailService;
import com.ruoyi.hospital.service.ITcmPatientService;
import com.ruoyi.hospital.service.ITcmRoomService;
import com.ruoyi.hospital.service.ITcmServiceTypeService;
import com.ruoyi.system.mapper.SysUserMapper;

@ExtendWith(MockitoExtension.class)
class TcmAppointmentNotificationServiceImplTest
{
    private static final ZoneId CLINIC_ZONE = ZoneId.of("America/Toronto");
    private static final DateTimeFormatter MYSQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Mock
    private TcmAppointmentMapper appointmentMapper;

    @Mock
    private ITcmPatientService patientService;

    @Mock
    private ITcmEmailService emailService;

    @Mock
    private ITcmBranchService branchService;

    @Mock
    private ITcmRoomService roomService;

    @Mock
    private ITcmServiceTypeService serviceTypeService;

    @Mock
    private TcmClinicSettingMapper clinicSettingMapper;

    @Mock
    private SysUserMapper userMapper;

    private TcmAppointmentNotificationServiceImpl service;

    @BeforeEach
    void setUp()
    {
        service = new TcmAppointmentNotificationServiceImpl();
        ReflectionTestUtils.setField(service, "appointmentMapper", appointmentMapper);
        ReflectionTestUtils.setField(service, "patientService", patientService);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "branchService", branchService);
        ReflectionTestUtils.setField(service, "roomService", roomService);
        ReflectionTestUtils.setField(service, "serviceTypeService", serviceTypeService);
        ReflectionTestUtils.setField(service, "clinicSettingMapper", clinicSettingMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "publicAppBaseUrl", "http://test.local");
        lenient().when(appointmentMapper.updateNotificationPayload(any(), any())).thenReturn(1);
        lenient().when(appointmentMapper.updateNotificationIntake(any(), any())).thenReturn(1);
        lenient().when(appointmentMapper.cancelNotificationAppointment(any(), any())).thenReturn(1);
        lenient().when(emailService.sendTemplateAndLog(anyString(), anyString(), any(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    @Test
    void handleAppointmentCreated_shouldSendConfirmationAndInternalBookingEmails()
    {
        TcmAppointment appointment = appointment(
                "apt-1",
                "pat-1",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "booked",
                LocalDateTime.now(CLINIC_ZONE).plusDays(2),
                "{}");
        AtomicReference<TcmAppointment> stored = new AtomicReference<>(appointment);
        when(appointmentMapper.selectTcmAppointmentById("apt-1")).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(appointmentMapper).updateNotificationPayload(any(TcmAppointment.class), any(TcmAppointment.class));

        TcmPatient patient = patient("pat-1", "张三", "patient@example.com", 0, "{}");
        when(patientService.selectTcmPatientById("pat-1")).thenReturn(patient);
        when(patientService.generateConsentToken("pat-1")).thenReturn("consent-token-1");
        when(branchService.selectTcmBranchById("branch-1"))
                .thenReturn(branch("branch-1", "仁和中医", "深圳市南山区科技园", "branch@example.com"));
        when(roomService.selectTcmRoomById("room-1")).thenReturn(room("room-1", "A诊室"));
        when(serviceTypeService.selectByKey("acupuncture_new")).thenReturn(serviceType("acupuncture_new", "针灸", 60));
        when(userMapper.selectUserById(101L)).thenReturn(practitioner(101L, "李医生", "doctor@example.com"));

        service.handleAppointmentCreated(appointment);

        verify(patientService).generateConsentToken("pat-1");
        verify(emailService).sendTemplateAndLog(
                eq("patient@example.com"),
                eq("appointmentConfirmation"),
                argThat(variables -> String.valueOf(variables.get("manageLink")).contains("/manage/")
                        && String.valueOf(variables.get("intakeLink")).contains("/intake/")
                        && String.valueOf(variables.get("consentLink")).contains("/consent/")),
                eq("仁和中医｜预约确认"),
                anyString(),
                eq("appointment_confirmation"));
        verify(emailService).sendTemplateAndLog(
                eq("branch@example.com"),
                eq("internalBooking"),
                any(),
                eq("仁和中医｜新预约通知"),
                anyString(),
                eq("appointment_internal_new"));
        verify(emailService).sendTemplateAndLog(
                eq("doctor@example.com"),
                eq("internalBooking"),
                any(),
                eq("仁和中医｜新预约通知"),
                anyString(),
                eq("appointment_internal_new"));

        JSONObject payload = JSONObject.parseObject(stored.get().getPayload());
        assertTrue(StringUtils.isNotBlank(payload.getString("manageToken")));
        assertTrue(StringUtils.isNotBlank(payload.getString("confirmationEmailSentAt")));
        assertTrue(StringUtils.isNotBlank(payload.getString("internalBookingEmailSentAt")));
        assertTrue(StringUtils.isNotBlank(stored.get().getIntakeToken()));
        assertEquals(Integer.valueOf(0), stored.get().getIntakeSubmitted());

        TcmAppointment beforeConfirm = appointment(
                "apt-1",
                "pat-1",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "booked",
                LocalDateTime.now(CLINIC_ZONE).plusDays(2),
                stored.get().getPayload());
        TcmAppointment confirmed = appointment(
                "apt-1",
                "pat-1",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "confirmed",
                LocalDateTime.now(CLINIC_ZONE).plusDays(2),
                stored.get().getPayload());

        service.handleAppointmentStatusChanged(beforeConfirm, confirmed);

        verify(patientService, times(1)).generateConsentToken("pat-1");
        verify(emailService, times(1)).sendTemplateAndLog(
                eq("patient@example.com"),
                eq("appointmentConfirmation"),
                any(),
                anyString(),
                anyString(),
                eq("appointment_confirmation"));
    }

    @Test
    void handleAppointmentUpdated_shouldSendChangeNotificationWhenKeyFieldsChange()
    {
        TcmAppointment before = appointment(
                "apt-2",
                "pat-2",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "booked",
                LocalDateTime.now(CLINIC_ZONE).plusDays(3),
                "{\"manageToken\":\"old-token\"}");
        TcmAppointment after = appointment(
                "apt-2",
                "pat-2",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "booked",
                LocalDateTime.now(CLINIC_ZONE).plusDays(3).plusHours(1),
                "{}");
        AtomicReference<TcmAppointment> stored = new AtomicReference<>(after);
        when(appointmentMapper.selectTcmAppointmentById("apt-2")).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(appointmentMapper).updateNotificationPayload(any(TcmAppointment.class), any(TcmAppointment.class));

        TcmPatient patient = patient("pat-2", "李四", "change@example.com", 1,
                "{\"latestIntakeCompleted\":true,\"latestIntakeSource\":\"public_intake_form\"}");
        when(patientService.selectTcmPatientById("pat-2")).thenReturn(patient);
        when(branchService.selectTcmBranchById("branch-1"))
                .thenReturn(branch("branch-1", "仁和中医", "深圳市南山区科技园", "branch@example.com"));
        when(roomService.selectTcmRoomById("room-1")).thenReturn(room("room-1", "A诊室"));
        when(serviceTypeService.selectByKey("acupuncture_new")).thenReturn(serviceType("acupuncture_new", "针灸", 60));
        when(userMapper.selectUserById(101L)).thenReturn(practitioner(101L, "李医生", "doctor@example.com"));

        service.handleAppointmentUpdated(before, after);

        verify(emailService).sendTemplateAndLog(
                eq("change@example.com"),
                eq("appointmentChange"),
                any(),
                eq("仁和中医｜预约变动通知"),
                anyString(),
                eq("appointment_change"));
        verify(emailService).sendTemplateAndLog(
                eq("branch@example.com"),
                eq("internalAppointmentChange"),
                any(),
                eq("仁和中医｜预约变动通知"),
                anyString(),
                eq("appointment_change_internal"));

        JSONObject payload = JSONObject.parseObject(stored.get().getPayload());
        assertTrue(StringUtils.isNotBlank(payload.getString("manageToken")));
        assertTrue(StringUtils.isNotBlank(payload.getString("changeEmailSentAt")));
    }

    @Test
    void handleAppointmentStatusChanged_shouldSendCancelNotificationWhenCancelled()
    {
        TcmAppointment before = appointment(
                "apt-3",
                "pat-3",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "booked",
                LocalDateTime.now(CLINIC_ZONE).plusDays(1),
                "{}");
        TcmAppointment after = appointment(
                "apt-3",
                "pat-3",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "cancelled",
                LocalDateTime.now(CLINIC_ZONE).plusDays(1),
                "{}");
        AtomicReference<TcmAppointment> stored = new AtomicReference<>(after);
        when(appointmentMapper.selectTcmAppointmentById("apt-3")).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(appointmentMapper).updateNotificationPayload(any(TcmAppointment.class), any(TcmAppointment.class));

        TcmPatient patient = patient("pat-3", "王五", "cancel@example.com", 1, "{}");
        when(patientService.selectTcmPatientById("pat-3")).thenReturn(patient);
        when(branchService.selectTcmBranchById("branch-1"))
                .thenReturn(branch("branch-1", "仁和中医", "深圳市南山区科技园", "branch@example.com"));
        when(roomService.selectTcmRoomById("room-1")).thenReturn(room("room-1", "A诊室"));
        when(serviceTypeService.selectByKey("acupuncture_new")).thenReturn(serviceType("acupuncture_new", "针灸", 60));
        when(userMapper.selectUserById(101L)).thenReturn(practitioner(101L, "李医生", "doctor@example.com"));

        service.handleAppointmentStatusChanged(before, after);

        verify(emailService).sendTemplateAndLog(
                eq("cancel@example.com"),
                eq("appointmentCancellation"),
                any(),
                eq("仁和中医｜预约取消通知"),
                anyString(),
                eq("appointment_cancel"));
        verify(emailService).sendTemplateAndLog(
                eq("branch@example.com"),
                eq("internalAppointmentCancellation"),
                any(),
                eq("仁和中医｜预约取消通知"),
                anyString(),
                eq("appointment_cancel_internal"));

        JSONObject payload = JSONObject.parseObject(stored.get().getPayload());
        assertTrue(StringUtils.isNotBlank(payload.getString("cancelledAt")));
        assertEquals("system", payload.getString("cancellationSource"));
    }

    @Test
    void handleAppointmentStatusChanged_shouldSendAftercareNotificationWhenCompleted()
    {
        TcmAppointment before = appointment(
                "apt-4",
                "pat-4",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "booked",
                LocalDateTime.now(CLINIC_ZONE).minusHours(3),
                "{}");
        TcmAppointment after = appointment(
                "apt-4",
                "pat-4",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "completed",
                LocalDateTime.now(CLINIC_ZONE).minusHours(3),
                "{}");
        AtomicReference<TcmAppointment> stored = new AtomicReference<>(after);
        when(appointmentMapper.selectTcmAppointmentById("apt-4")).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(appointmentMapper).updateNotificationPayload(any(TcmAppointment.class), any(TcmAppointment.class));

        TcmPatient patient = patient("pat-4", "赵六", "aftercare@example.com", 1, "{}");
        when(patientService.selectTcmPatientById("pat-4")).thenReturn(patient);
        when(branchService.selectTcmBranchById("branch-1"))
                .thenReturn(branch("branch-1", "仁和中医", "深圳市南山区科技园", "branch@example.com"));

        service.handleAppointmentStatusChanged(before, after);

        verify(emailService).sendTemplateAndLog(
                eq("aftercare@example.com"),
                eq("aftercare"),
                any(),
                eq("仁和中医｜治疗后护理提醒"),
                anyString(),
                eq("appointment_aftercare"));

        JSONObject payload = JSONObject.parseObject(stored.get().getPayload());
        assertTrue(StringUtils.isNotBlank(payload.getString("treatmentCompletedAt")));
    }

    @Test
    void processDueNotifications_shouldSendReminderAftercareAndFollowUpForDueAppointments()
    {
        LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
        String reminderStart = now.plusHours(2).format(MYSQL_DATETIME);
        String completedAt = now.minusDays(3).minusMinutes(5).format(MYSQL_DATETIME);

        TcmAppointment reminder = appointment(
                "apt-5",
                "pat-5",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "booked",
                LocalDateTime.parse(reminderStart, MYSQL_DATETIME),
                "{}");
        TcmAppointment followUp = appointment(
                "apt-6",
                "pat-6",
                "branch-1",
                "101",
                "room-1",
                "acupuncture_new",
                "completed",
                now.minusDays(4),
                "{\"treatmentCompletedAt\":\"" + completedAt + "\"}");

        TcmAppointment imported = appointment("apt-imported", "pat-imported", "branch-1", "101", "room-1",
                "acupuncture_new", "booked", now.plusHours(2), "{\"notificationOwnerInstance\":\"legacy\"}");
        ReflectionTestUtils.setField(service, "instanceId", "yuanyuan");
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(Arrays.asList(imported, reminder, followUp));
        when(appointmentMapper.selectTcmAppointmentById(anyString())).thenAnswer(invocation ->
                Arrays.asList(imported, reminder, followUp).stream()
                        .filter(item -> item.getId().equals(invocation.getArgument(0))).findFirst().orElse(null));
        when(patientService.selectTcmPatientById("pat-5")).thenReturn(patient("pat-5", "提醒患者", "reminder@example.com", 1, "{}"));
        when(patientService.selectTcmPatientById("pat-6")).thenReturn(patient("pat-6", "回访患者", "follow@example.com", 1, "{}"));
        when(branchService.selectTcmBranchById("branch-1"))
                .thenReturn(branch("branch-1", "仁和中医", "深圳市南山区科技园", "branch@example.com"));

        service.processDueNotifications();

        verify(emailService).sendTemplateAndLog(
                eq("reminder@example.com"),
                eq("reminder"),
                any(),
                eq("仁和中医｜预约提醒"),
                anyString(),
                eq("appointment_reminder"));
        verify(emailService).sendTemplateAndLog(
                eq("follow@example.com"),
                eq("aftercare"),
                any(),
                anyString(),
                anyString(),
                eq("appointment_aftercare"));
        verify(emailService).sendTemplateAndLog(
                eq("follow@example.com"),
                eq("followUp"),
                any(),
                eq("仁和中医｜治疗后回访"),
                anyString(),
                eq("appointment_follow_up"));
        verify(emailService, times(3)).sendTemplateAndLog(anyString(), anyString(), any(), anyString(), anyString(), anyString());
        verify(patientService, org.mockito.Mockito.never()).selectTcmPatientById("pat-imported");

        JSONObject reminderPayload = JSONObject.parseObject(reminder.getPayload());
        assertTrue(StringUtils.isNotBlank(reminderPayload.getString("reminderEmailSentAt")));
        JSONObject followUpPayload = JSONObject.parseObject(followUp.getPayload());
        assertTrue(StringUtils.isNotBlank(followUpPayload.getString("aftercareEmailSentAt")));
        assertTrue(StringUtils.isNotBlank(followUpPayload.getString("followUpEmailSentAt")));
    }

    @Test
    void failedAftercareShouldRetryAndOnlyMarkSuccessfulDelivery()
    {
        TcmAppointment completed = appointment("retry", "patient", null, null, null, "consultation", "completed",
                LocalDateTime.now(CLINIC_ZONE).minusHours(3), "{}");
        when(appointmentMapper.selectTcmAppointmentById("retry")).thenReturn(completed);
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(completed));
        when(patientService.selectTcmPatientById("patient")).thenReturn(patient("patient", "Retry", "retry@example.com", 1, "{}"));
        when(emailService.sendTemplateAndLog(anyString(), eq("aftercare"), any(), anyString(), anyString(), anyString())).thenReturn(false, true);
        service.processDueNotifications();
        JSONObject failed = JSONObject.parseObject(completed.getPayload());
        assertTrue(StringUtils.isBlank(failed.getString("aftercareEmailSentAt")));
        assertTrue(StringUtils.isBlank(failed.getString("aftercareEmailSentAtPendingAt")));
        service.processDueNotifications();
        assertTrue(StringUtils.isNotBlank(JSONObject.parseObject(completed.getPayload()).getString("aftercareEmailSentAt")));
        service.processDueNotifications();
        verify(emailService, times(2)).sendTemplateAndLog(anyString(), eq("aftercare"), any(), anyString(), anyString(), anyString());
    }

    @Test
    void locallyCompletedImportedAppointmentShouldRetryAftercareAndSendDueFollowUp()
    {
        ReflectionTestUtils.setField(service, "instanceId", "yuanyuan");
        LocalDateTime start = LocalDateTime.now(CLINIC_ZONE).minusHours(3);
        String importedPayload = "{\"notificationOwnerInstance\":\"legacy\"}";
        TcmAppointment before = appointment("import-complete", "patient", null, null, null,
                "consultation", "booked", start, importedPayload);
        TcmAppointment completed = appointment("import-complete", "patient", null, null, null,
                "consultation", "completed", start, importedPayload);
        when(appointmentMapper.selectTcmAppointmentById(completed.getId())).thenReturn(completed);
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(completed));
        when(patientService.selectTcmPatientById("patient"))
                .thenReturn(patient("patient", "Local completion", "local@example.com", 1, "{}"));
        when(emailService.sendTemplateAndLog(anyString(), eq("aftercare"), any(), anyString(), anyString(), anyString()))
                .thenReturn(false, true);

        service.handleAppointmentStatusChanged(before, completed);
        JSONObject payload = JSONObject.parseObject(completed.getPayload());
        assertEquals("yuanyuan", payload.getString("notificationOwnerInstance"));
        assertTrue(StringUtils.isNotBlank(payload.getString("treatmentCompletedAt")));
        assertTrue(StringUtils.isBlank(payload.getString("aftercareEmailSentAt")));

        service.processDueNotifications();
        verify(emailService, times(2)).sendTemplateAndLog(anyString(), eq("aftercare"), any(), anyString(), anyString(), anyString());
        verify(emailService, org.mockito.Mockito.never()).sendTemplateAndLog(anyString(), eq("followUp"), any(), anyString(), anyString(), anyString());
        payload = JSONObject.parseObject(completed.getPayload());
        assertTrue(StringUtils.isNotBlank(payload.getString("aftercareEmailSentAt")));

        payload.put("treatmentCompletedAt", LocalDateTime.now(CLINIC_ZONE).minusDays(3).minusMinutes(1).format(MYSQL_DATETIME));
        completed.setPayload(payload.toJSONString());
        completed.setEndTime(payload.getString("treatmentCompletedAt"));
        service.processDueNotifications();
        service.processDueNotifications();
        verify(emailService).sendTemplateAndLog(anyString(), eq("followUp"), any(), anyString(), anyString(), eq("appointment_follow_up"));
        verify(emailService, times(2)).sendTemplateAndLog(anyString(), eq("aftercare"), any(), anyString(), anyString(), anyString());
    }

    @Test
    void importedCompletedAppointmentWithoutLocalTransitionShouldRemainOwnedBySource()
    {
        ReflectionTestUtils.setField(service, "instanceId", "yuanyuan");
        String importedPayload = "{\"notificationOwnerInstance\":\"legacy\",\"treatmentCompletedAt\":\""
                + LocalDateTime.now(CLINIC_ZONE).minusDays(4).format(MYSQL_DATETIME) + "\"}";
        TcmAppointment completed = appointment("import-history", "patient", null, null, null,
                "consultation", "completed", LocalDateTime.now(CLINIC_ZONE).minusDays(4), importedPayload);
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(completed));

        service.handleAppointmentStatusChanged(completed, completed);
        service.processDueNotifications();

        assertEquals(importedPayload, completed.getPayload());
        verify(appointmentMapper, org.mockito.Mockito.never()).updateTcmAppointment(any());
        org.mockito.Mockito.verifyNoInteractions(emailService, patientService);
    }

    @Test
    void localCompletionWithoutConfiguredInstanceIdShouldReleaseForeignNotificationOwner()
    {
        ReflectionTestUtils.setField(service, "instanceId", "");
        LocalDateTime start = LocalDateTime.now(CLINIC_ZONE).minusHours(3);
        String importedPayload = "{\"notificationOwnerInstance\":\"legacy\"}";
        TcmAppointment before = appointment("import-default", "patient", null, null, null,
                "consultation", "confirmed", start, importedPayload);
        TcmAppointment completed = appointment("import-default", "patient", null, null, null,
                "consultation", "completed", start, importedPayload);
        when(appointmentMapper.selectTcmAppointmentById(completed.getId())).thenReturn(completed);
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(completed));
        when(patientService.selectTcmPatientById("patient"))
                .thenReturn(patient("patient", "Local completion", "local@example.com", 1, "{}"));

        service.handleAppointmentStatusChanged(before, completed);
        JSONObject payload = JSONObject.parseObject(completed.getPayload());
        assertTrue(StringUtils.isBlank(payload.getString("notificationOwnerInstance")));
        payload.put("treatmentCompletedAt", LocalDateTime.now(CLINIC_ZONE).minusDays(3).minusMinutes(1).format(MYSQL_DATETIME));
        completed.setPayload(payload.toJSONString());
        completed.setEndTime(payload.getString("treatmentCompletedAt"));
        service.processDueNotifications();

        verify(emailService).sendTemplateAndLog(anyString(), eq("followUp"), any(), anyString(), anyString(), eq("appointment_follow_up"));
    }

    @Test
    void aftercareMustWaitUntilCompletionTransactionCommits()
    {
        TcmAppointment completed = appointment("commit", "patient", null, null, null, "consultation", "completed",
                LocalDateTime.now(CLINIC_ZONE).minusHours(3), "{}");
        when(appointmentMapper.selectTcmAppointmentById("commit")).thenReturn(completed);
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(completed));
        when(patientService.selectTcmPatientById("patient")).thenReturn(patient("patient", "Commit", "commit@example.com", 1, "{}"));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.processDueNotifications();
            verify(emailService, org.mockito.Mockito.never()).sendTemplateAndLog(anyString(), anyString(), any(), anyString(), anyString(), anyString());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(emailService).sendTemplateAndLog(anyString(), eq("aftercare"), any(), anyString(), anyString(), anyString());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void queuedCareNotificationsShouldNotSendAfterAppointmentIsCancelled()
    {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        ReflectionTestUtils.setField(service, "notificationTaskExecutor", (org.springframework.core.task.TaskExecutor) queued::add);
        for (String key : Arrays.asList("reminderEmailSentAt", "aftercareEmailSentAt", "followUpEmailSentAt"))
        {
            boolean reminder = key.startsWith("reminder");
            LocalDateTime now = LocalDateTime.now(CLINIC_ZONE);
            JSONObject payload = new JSONObject();
            payload.put("manageToken", "cancel-queue-token");
            payload.put("treatmentCompletedAt", now.minusDays(4).format(MYSQL_DATETIME));
            if (key.startsWith("aftercare")) payload.put("followUpEmailSentAt", now.toString());
            if (key.startsWith("followUp")) payload.put("aftercareEmailSentAt", now.toString());
            TcmAppointment appointment = appointment(key, "patient", null, null, null, "consultation",
                    reminder ? "booked" : "completed", reminder ? now.plusHours(2) : now.minusDays(4), payload.toJSONString());
            when(appointmentMapper.selectTcmAppointmentById(key)).thenReturn(appointment);
            when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(appointment));
            when(patientService.selectTcmPatientById("patient"))
                    .thenReturn(patient("patient", "Queue cancellation", "queue@example.com", 1, "{}"));

            service.processDueNotifications();
            assertEquals(1, queued.size());
            appointment.setStatus("cancelled");
            queued.remove(0).run();

            JSONObject finalPayload = JSONObject.parseObject(appointment.getPayload());
            assertTrue(StringUtils.isBlank(finalPayload.getString(key)));
            assertTrue(StringUtils.isBlank(finalPayload.getString(key + "PendingAt")));
            assertEquals("cancelled", appointment.getStatus());
        }
        org.mockito.Mockito.verifyNoInteractions(emailService);
    }

    @Test
    void staleSchedulerSnapshotShouldNotClaimReminderForCancelledAppointment()
    {
        LocalDateTime start = LocalDateTime.now(CLINIC_ZONE).plusHours(2);
        TcmAppointment stale = appointment("stale-reminder", "patient", null, null, null, "consultation", "booked", start, "{}");
        TcmAppointment cancelled = appointment("stale-reminder", "patient", null, null, null, "consultation", "cancelled", start, "{}");
        when(appointmentMapper.selectTcmAppointmentById(stale.getId())).thenReturn(cancelled);
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(stale));
        when(patientService.selectTcmPatientById("patient"))
                .thenReturn(patient("patient", "Cancelled", "cancelled@example.com", 1, "{}"));

        service.processDueNotifications();

        org.mockito.Mockito.verifyNoInteractions(emailService);
        verify(appointmentMapper, org.mockito.Mockito.never()).updateTcmAppointment(any());
    }

    @Test
    void staleCompletedSnapshotShouldNotOverwriteCancelledStateOrSendCareEmail()
    {
        LocalDateTime start = LocalDateTime.now(CLINIC_ZONE).minusDays(4);
        TcmAppointment stale = appointment("stale-completed", "patient", null, null, null, "consultation", "completed", start, "{}");
        AtomicReference<TcmAppointment> stored = new AtomicReference<>(appointment("stale-completed", "patient", null, null, null,
                "consultation", "cancelled", start, "{}"));
        when(appointmentMapper.selectTcmAppointmentById(stale.getId())).thenAnswer(invocation -> stored.get());
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(stale));
        lenient().doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(appointmentMapper).updateTcmAppointment(any());

        service.processDueNotifications();

        assertEquals("cancelled", stored.get().getStatus());
        org.mockito.Mockito.verifyNoInteractions(emailService);
        verify(appointmentMapper, org.mockito.Mockito.never()).updateTcmAppointment(any());
    }

    @Test
    void reschedulingAfterSentReminderShouldAllowReminderForNewTimeOnlyOnce()
    {
        LocalDateTime start = LocalDateTime.now(CLINIC_ZONE).plusHours(2);
        TcmAppointment appointment = appointment("reschedule-reminder", "patient", null, null, null, "consultation", "booked",
                start, "{\"manageToken\":\"reschedule-token\"}");
        when(appointmentMapper.selectTcmAppointmentById(appointment.getId())).thenReturn(appointment);
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(appointment));
        when(patientService.selectTcmPatientById("patient"))
                .thenReturn(patient("patient", "Rescheduled", "rescheduled@example.com", 1, "{}"));
        service.processDueNotifications();

        TcmAppointment before = appointment(appointment.getId(), "patient", null, null, null, "consultation", "booked", start, appointment.getPayload());
        appointment.setStartTime(start.plusHours(3).format(MYSQL_DATETIME));
        appointment.setEndTime(start.plusHours(4).format(MYSQL_DATETIME));
        service.handleAppointmentUpdated(before, appointment);
        service.processDueNotifications();
        service.handleAppointmentUpdated(appointment, appointment);
        service.processDueNotifications();

        verify(emailService, times(2)).sendTemplateAndLog(anyString(), eq("reminder"), any(), anyString(), anyString(), eq("appointment_reminder"));
    }

    @Test
    void queuedReminderForOldTimeShouldNotClearOrSendNewScheduleClaim()
    {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        ReflectionTestUtils.setField(service, "notificationTaskExecutor", (org.springframework.core.task.TaskExecutor) queued::add);
        LocalDateTime start = LocalDateTime.now(CLINIC_ZONE).plusHours(2);
        TcmAppointment appointment = appointment("queue-reschedule", "patient", null, null, null, "consultation", "booked",
                start, "{\"manageToken\":\"queue-reschedule-token\"}");
        when(appointmentMapper.selectTcmAppointmentById(appointment.getId())).thenReturn(appointment);
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(appointment));
        when(patientService.selectTcmPatientById("patient"))
                .thenReturn(patient("patient", "Queue reschedule", "queue@example.com", 1, "{}"));
        service.processDueNotifications();
        Runnable oldReminder = queued.remove(0);

        TcmAppointment before = appointment(appointment.getId(), "patient", null, null, null, "consultation", "booked", start, appointment.getPayload());
        String newStart = start.plusHours(3).format(MYSQL_DATETIME);
        appointment.setStartTime(newStart);
        appointment.setEndTime(start.plusHours(4).format(MYSQL_DATETIME));
        service.handleAppointmentUpdated(before, appointment);
        service.processDueNotifications();
        oldReminder.run();
        for (Runnable task : queued) task.run();
        queued.clear();
        service.processDueNotifications();

        assertTrue(queued.isEmpty());
        verify(emailService).sendTemplateAndLog(anyString(), eq("reminder"),
                argThat(variables -> newStart.equals(variables.get("appointmentStartTime"))), anyString(), anyString(), eq("appointment_reminder"));
        verify(emailService, times(1)).sendTemplateAndLog(anyString(), eq("reminder"), any(), anyString(), anyString(), anyString());
        assertTrue(StringUtils.isNotBlank(JSONObject.parseObject(appointment.getPayload()).getString("reminderEmailSentAt")));
    }

    @Test
    void eachRealRescheduleSendsChangeNoticeWhileRepeatedSameEventIsDeduplicated()
    {
        LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
        TcmAppointment current = appointment("repeat-change", "patient", null, null, null, "consultation", "booked",
                start, "{\"manageToken\":\"change-token\",\"changeEmailSentAt\":\"2026-10-01 09:00:00\"}");
        when(appointmentMapper.selectTcmAppointmentById(current.getId())).thenReturn(current);
        when(patientService.selectTcmPatientById("patient"))
                .thenReturn(patient("patient", "Change", "qa@example.invalid", 1, "{}"));
        // Include returning to the original time and then repeating a past transition.
        for (int hour : new int[] { 11, 12, 10, 11 })
        {
            TcmAppointment before = appointment(current.getId(), "patient", null, null, null, "consultation", "booked",
                    LocalDateTime.parse(current.getStartTime(), MYSQL_DATETIME), current.getPayload());
            current.setStartTime(start.withHour(hour).format(MYSQL_DATETIME));
            current.setEndTime(start.withHour(hour + 1).format(MYSQL_DATETIME));
            service.handleAppointmentUpdated(before, current);
            service.handleAppointmentUpdated(before, current);
        }
        verify(emailService, times(4)).sendTemplateAndLog(anyString(), eq("appointmentChange"), any(), anyString(), anyString(), eq("appointment_change"));
    }

    @Test
    void staleRescheduleCallbackCannotSendNoticeForAnOlderAppointmentTime()
    {
        LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
        TcmAppointment before = appointment("stale-change", "patient", null, null, null, "consultation", "booked", start,
                "{\"manageToken\":\"change-token\"}");
        TcmAppointment after = appointment(before.getId(), "patient", null, null, null, "consultation", "booked", start.plusHours(1), before.getPayload());
        TcmAppointment latest = appointment(before.getId(), "patient", null, null, null, "consultation", "booked", start.plusHours(2), before.getPayload());
        when(appointmentMapper.selectTcmAppointmentById(before.getId())).thenReturn(latest);
        service.handleAppointmentUpdated(before, after);
        org.mockito.Mockito.verifyNoInteractions(emailService);
    }

    private TcmAppointment appointment(String id, String patientId, String branchId, String practitionerId,
            String roomId, String serviceType, String status, LocalDateTime startTime, String payload)
    {
        TcmAppointment appointment = new TcmAppointment();
        appointment.setId(id);
        appointment.setPatientId(patientId);
        appointment.setBranchId(branchId);
        appointment.setPractitionerId(practitionerId);
        appointment.setRoomId(roomId);
        appointment.setServiceType(serviceType);
        appointment.setStatus(status);
        appointment.setStartTime(startTime.format(MYSQL_DATETIME));
        appointment.setEndTime(startTime.plusMinutes(60).format(MYSQL_DATETIME));
        appointment.setPayload(payload);
        return appointment;
    }

    @Test
    void careScheduleUsesTorontoAppointmentEndInsteadOfCompletionClickTime()
    {
        java.time.Instant now = java.time.Instant.parse("2026-10-13T17:00:00Z"); // 13:00 Toronto
        ReflectionTestUtils.setField(service, "clock", java.time.Clock.fixed(now, CLINIC_ZONE));
        TcmAppointment appt = appointment("boundary", "patient", null, null, null, "consultation", "completed",
                LocalDateTime.of(2026, 10, 13, 11, 0), "{\"treatmentCompletedAt\":\"2026-10-13 12:55:00\"}");
        when(appointmentMapper.selectTcmAppointmentById(appt.getId())).thenReturn(appt);
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(appt));
        when(patientService.selectTcmPatientById("patient")).thenReturn(patient("patient", "Boundary", "qa@example.invalid", 1, "{}"));
        appt.setEndTime("2026-10-13 12:00:01");
        service.processDueNotifications();
        org.mockito.Mockito.verifyNoInteractions(emailService);
        appt.setEndTime("2026-10-13 12:00:00");
        service.processDueNotifications();
        service.processDueNotifications();
        verify(emailService).sendTemplateAndLog(anyString(), eq("aftercare"), any(), anyString(), anyString(), anyString());
        ReflectionTestUtils.setField(service, "clock", java.time.Clock.fixed(java.time.Instant.parse("2026-10-16T15:59:59Z"), CLINIC_ZONE));
        service.processDueNotifications();
        verify(emailService, org.mockito.Mockito.never()).sendTemplateAndLog(anyString(), eq("followUp"), any(), anyString(), anyString(), anyString());
        ReflectionTestUtils.setField(service, "clock", java.time.Clock.fixed(java.time.Instant.parse("2026-10-16T16:00:00Z"), CLINIC_ZONE));
        service.processDueNotifications();
        service.processDueNotifications();
        verify(emailService).sendTemplateAndLog(anyString(), eq("followUp"), any(), anyString(), anyString(), anyString());
    }

    @Test
    void completedFutureAppointmentDoesNotSendAftercareEarly()
    {
        TcmAppointment appt = appointment("future-completion", "patient", null, null, null, "consultation", "completed",
                LocalDateTime.now(CLINIC_ZONE).plusDays(1), "{}");
        when(appointmentMapper.selectTcmAppointmentById(appt.getId())).thenReturn(appt);
        when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(appt));
        lenient().when(patientService.selectTcmPatientById("patient")).thenReturn(patient("patient", "Future", "qa@example.invalid", 1, "{}"));
        service.processDueNotifications();
        org.mockito.Mockito.verifyNoInteractions(emailService);
    }

    @Test
    void confirmationExplainsAlreadySignedConsentInsteadOfLeavingBlankLink()
    {
        TcmAppointment appt = appointment("signed", "patient", null, null, null, "consultation", "booked",
                LocalDateTime.now(CLINIC_ZONE).plusDays(1), "{}");
        when(appointmentMapper.selectTcmAppointmentById(appt.getId())).thenReturn(appt);
        when(patientService.selectTcmPatientById("patient")).thenReturn(patient("patient", "Signed", "qa@example.invalid", 1, "{\"latestIntakeCompleted\":true,\"latestIntakeSource\":\"public_intake_form\"}"));
        service.handleAppointmentCreated(appt);
        verify(emailService).sendTemplateAndLog(anyString(), eq("appointmentConfirmation"),
                argThat(variables -> "Already signed — no further action required.".equals(variables.get("consentLink"))),
                anyString(), anyString(), anyString());
        verify(patientService, org.mockito.Mockito.never()).generateConsentToken(anyString());
    }

    @Test
    void bookedAndConfirmedAppointmentsReceiveCareOnScheduleWithoutManualCompletion()
    {
        ReflectionTestUtils.setField(service, "clock", java.time.Clock.fixed(java.time.Instant.parse("2026-10-16T17:00:00Z"), CLINIC_ZONE));
        for (String status : Arrays.asList("booked", "confirmed")) {
            TcmAppointment appt = appointment(status, "patient", null, null, null, "consultation", status,
                    LocalDateTime.of(2026, 10, 13, 11, 0), "{}");
            when(appointmentMapper.selectTcmAppointmentById(appt.getId())).thenReturn(appt);
            when(appointmentMapper.selectTcmAppointmentList(any())).thenReturn(java.util.Collections.singletonList(appt));
            when(patientService.selectTcmPatientById("patient")).thenReturn(patient("patient", "Scheduled", "qa@example.invalid", 1, "{}"));
            service.processDueNotifications(); service.processDueNotifications();
            assertEquals(status, appt.getStatus());
            JSONObject payload = JSONObject.parseObject(appt.getPayload());
            assertTrue(StringUtils.isNotBlank(payload.getString("aftercareEmailSentAt")));
            assertTrue(StringUtils.isNotBlank(payload.getString("followUpEmailSentAt")));
            assertTrue(StringUtils.isBlank(payload.getString("treatmentCompletedAt")));
        }
        verify(emailService, times(2)).sendTemplateAndLog(anyString(), eq("aftercare"), any(), anyString(), anyString(), anyString());
        verify(emailService, times(2)).sendTemplateAndLog(anyString(), eq("followUp"), any(), anyString(), anyString(), anyString());
    }

    private TcmPatient patient(String id, String name, String email, Integer consentSigned, String payload)
    {
        TcmPatient patient = new TcmPatient();
        patient.setId(id);
        patient.setName(name);
        patient.setEmail(email);
        patient.setConsentSigned(consentSigned);
        patient.setPayload(payload);
        return patient;
    }

    private TcmBranch branch(String id, String name, String address, String email)
    {
        TcmBranch branch = new TcmBranch();
        branch.setId(id);
        branch.setName(name);
        branch.setAddress(address);
        branch.setEmail(email);
        return branch;
    }

    private TcmRoom room(String id, String name)
    {
        TcmRoom room = new TcmRoom();
        room.setId(id);
        room.setName(name);
        return room;
    }

    private TcmServiceType serviceType(String key, String label, Integer duration)
    {
        TcmServiceType serviceType = new TcmServiceType();
        serviceType.setServiceKey(key);
        serviceType.setLabel(label);
        serviceType.setDuration(duration);
        serviceType.setRoomRequired(1);
        return serviceType;
    }

    private SysUser practitioner(long id, String nickName, String email)
    {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setNickName(nickName);
        user.setEmail(email);
        return user;
    }
}
