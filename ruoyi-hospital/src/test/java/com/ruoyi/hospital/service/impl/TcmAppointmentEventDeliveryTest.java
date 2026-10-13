package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.domain.*;
import com.ruoyi.hospital.mapper.*;
import com.ruoyi.hospital.service.*;
import com.ruoyi.system.mapper.SysUserMapper;

class TcmAppointmentEventDeliveryTest
{
    private TcmAppointmentNotificationServiceImpl service;
    private TcmAppointmentMapper mapper;
    private ITcmPatientService patients;
    private ITcmEmailService email;
    private ITcmBranchService branches;
    private SysUserMapper users;
    private TcmAppointment appointment;
    private TcmPatient patient;
    private final Instant now = Instant.parse("2026-10-13T17:00:00Z");

    @BeforeEach void setup()
    {
        mapper = mock(TcmAppointmentMapper.class); patients = mock(ITcmPatientService.class);
        email = mock(ITcmEmailService.class); branches = mock(ITcmBranchService.class); users = mock(SysUserMapper.class);
        when(mapper.updateNotificationPayload(any(), any())).thenReturn(1);
        when(mapper.updateNotificationIntake(any(), any())).thenReturn(1);
        when(mapper.cancelNotificationAppointment(any(), any())).thenReturn(1);
        when(email.sendTemplateAndLog(anyString(), anyString(), any(), anyString(), anyString(), anyString())).thenReturn(true);
        appointment = new TcmAppointment(); appointment.setId("event"); appointment.setPatientId("patient");
        appointment.setStatus("booked"); appointment.setServiceType("consultation");
        appointment.setStartTime("2026-10-20 10:00:00"); appointment.setEndTime("2026-10-20 11:00:00");
        appointment.setPayload("{\"manageToken\":\"manage\"}");
        when(mapper.selectTcmAppointmentById("event")).thenAnswer(call -> appointment);
        when(mapper.selectTcmAppointmentList(any())).thenAnswer(call -> Collections.singletonList(appointment));
        patient = new TcmPatient(); patient.setId("patient"); patient.setName("QA"); patient.setEmail("qa@example.invalid");
        patient.setConsentSigned(1); patient.setPayload("{\"latestIntakeCompleted\":true,\"latestIntakeSource\":\"public_intake_form\"}");
        when(patients.selectTcmPatientById("patient")).thenReturn(patient);
        service = newService(now);
    }

    private TcmAppointmentNotificationServiceImpl newService(Instant time)
    {
        TcmAppointmentNotificationServiceImpl result = new TcmAppointmentNotificationServiceImpl();
        ReflectionTestUtils.setField(result, "appointmentMapper", mapper);
        ReflectionTestUtils.setField(result, "patientService", patients);
        ReflectionTestUtils.setField(result, "emailService", email);
        ReflectionTestUtils.setField(result, "branchService", branches);
        ReflectionTestUtils.setField(result, "roomService", mock(ITcmRoomService.class));
        ReflectionTestUtils.setField(result, "serviceTypeService", mock(ITcmServiceTypeService.class));
        ReflectionTestUtils.setField(result, "clinicSettingMapper", mock(TcmClinicSettingMapper.class));
        ReflectionTestUtils.setField(result, "userMapper", users);
        ReflectionTestUtils.setField(result, "publicAppBaseUrl", "https://test.invalid");
        ReflectionTestUtils.setField(result, "clock", Clock.fixed(time, ZoneId.of("America/Toronto")));
        return result;
    }

    private JSONObject payload() { return JSONObject.parseObject(appointment.getPayload()); }
    private TcmAppointment snapshot() { return JSONObject.parseObject(JSONObject.toJSONString(appointment), TcmAppointment.class); }

    @Test void failedConfirmationRetriesStoredConsentLinkAndMarksOnlySuccessfulDelivery()
    {
        patient.setConsentSigned(0); when(patients.generateConsentToken("patient")).thenReturn("stable-consent");
        when(email.sendTemplateAndLog(anyString(), eq("appointmentConfirmation"), any(), anyString(), anyString(), anyString())).thenReturn(false, true);
        service.handleAppointmentCreated(appointment);
        assertFalse(payload().containsKey("confirmationEmailSentAt"));
        assertTrue(payload().getJSONObject("notificationOutbox").containsKey("confirmationEmailSentAt"));
        service.processDueNotifications(); service.processDueNotifications(); service.handleAppointmentCreated(appointment);
        verify(email, times(2)).sendTemplateAndLog(anyString(), eq("appointmentConfirmation"),
                argThat(vars -> String.valueOf(vars.get("consentLink")).endsWith("/stable-consent")), anyString(), anyString(), anyString());
        verify(patients, times(1)).generateConsentToken("patient");
        assertTrue(payload().containsKey("confirmationEmailSentAt"));
    }

    @Test void partialInternalSuccessRetriesOnlyFailedRecipient()
    {
        appointment.setBranchId("branch"); appointment.setPractitionerId("1");
        TcmBranch branch = new TcmBranch(); branch.setEmail("branch@example.invalid");
        when(branches.selectTcmBranchById("branch")).thenReturn(branch);
        SysUser user = new SysUser(); user.setEmail("doctor@example.invalid"); when(users.selectUserById(1L)).thenReturn(user);
        when(email.sendTemplateAndLog(eq("doctor@example.invalid"), eq("internalBooking"), any(), anyString(), anyString(), anyString())).thenReturn(false, true);
        service.handleAppointmentCreated(appointment);
        assertFalse(payload().containsKey("internalBookingEmailSentAt"));
        service.processDueNotifications(); service.processDueNotifications();
        verify(email, times(1)).sendTemplateAndLog(eq("branch@example.invalid"), eq("internalBooking"), any(), anyString(), anyString(), anyString());
        verify(email, times(2)).sendTemplateAndLog(eq("doctor@example.invalid"), eq("internalBooking"), any(), anyString(), anyString(), anyString());
        assertTrue(payload().containsKey("internalBookingEmailSentAt"));
    }

    @Test void cancellationDiscardsQueuedConfirmationAndChange()
    {
        List<Runnable> tasks = new ArrayList<>(); ReflectionTestUtils.setField(service, "notificationTaskExecutor", (TaskExecutor) tasks::add);
        service.handleAppointmentCreated(appointment);
        TcmAppointment before = snapshot(); appointment.setStartTime("2026-10-20 12:00:00"); appointment.setEndTime("2026-10-20 13:00:00");
        service.handleAppointmentUpdated(before, appointment);
        appointment.setStatus("cancelled");
        tasks.forEach(Runnable::run); service.processDueNotifications();
        verifyNoInteractions(email);
        assertTrue(payload().getJSONObject("notificationOutbox").isEmpty());
        assertFalse(payload().containsKey("confirmationEmailSentAt")); assertFalse(payload().containsKey("changeEmailSentAt"));
    }

    @Test void expiredClaimSurvivesRestartAndOldWorkerCannotSendAgain()
    {
        List<Runnable> oldTasks = new ArrayList<>(); ReflectionTestUtils.setField(service, "notificationTaskExecutor", (TaskExecutor) oldTasks::add);
        service.handleAppointmentCreated(appointment);
        TcmAppointmentNotificationServiceImpl restarted = newService(now.plusSeconds(11 * 60));
        restarted.processDueNotifications(); oldTasks.forEach(Runnable::run); restarted.processDueNotifications();
        verify(email, times(1)).sendTemplateAndLog(anyString(), eq("appointmentConfirmation"), any(), anyString(), anyString(), anyString());
        assertTrue(payload().getJSONObject("notificationOutbox").isEmpty());
    }

    @Test void failedChangeAndCancellationAreRetriedWithoutDuplicateSuccess()
    {
        when(email.sendTemplateAndLog(anyString(), eq("appointmentChange"), any(), anyString(), anyString(), anyString())).thenReturn(false, true);
        when(email.sendTemplateAndLog(anyString(), eq("appointmentCancellation"), any(), anyString(), anyString(), anyString())).thenReturn(false, true);
        TcmAppointment before = snapshot(); appointment.setStartTime("2026-10-20 12:00:00"); appointment.setEndTime("2026-10-20 13:00:00");
        service.handleAppointmentUpdated(before, appointment);
        assertFalse(payload().containsKey("changeEmailSentAt")); service.processDueNotifications(); service.processDueNotifications();
        before = snapshot(); appointment.setStatus("cancelled"); service.handleAppointmentStatusChanged(before, appointment);
        assertFalse(payload().containsKey("cancelEmailSentAt")); service.processDueNotifications(); service.processDueNotifications();
        verify(email, times(2)).sendTemplateAndLog(anyString(), eq("appointmentChange"), any(), anyString(), anyString(), anyString());
        verify(email, times(2)).sendTemplateAndLog(anyString(), eq("appointmentCancellation"), any(), anyString(), anyString(), anyString());
        assertTrue(payload().containsKey("changeEmailSentAt")); assertTrue(payload().containsKey("cancelEmailSentAt"));
    }

    @Test void importedAppointmentOutboxIsNotClaimedByAnotherInstance()
    {
        List<Runnable> tasks = new ArrayList<>(); ReflectionTestUtils.setField(service, "notificationTaskExecutor", (TaskExecutor) tasks::add);
        service.handleAppointmentCreated(appointment);
        JSONObject data = payload(); data.put("notificationOwnerInstance", "source"); appointment.setPayload(data.toJSONString());
        TcmAppointmentNotificationServiceImpl restarted = newService(now.plusSeconds(11 * 60));
        ReflectionTestUtils.setField(restarted, "instanceId", "destination");
        restarted.processDueNotifications(); verifyNoInteractions(email);
        assertTrue(payload().getJSONObject("notificationOutbox").containsKey("confirmationEmailSentAt"));
    }

    @Test void failedPayloadCompareAndSetCannotDispatchAnUnpersistedEvent()
    {
        when(mapper.updateNotificationPayload(any(), any())).thenReturn(0);
        assertThrows(ServiceException.class, () -> service.handleAppointmentCreated(appointment));
        verifyNoInteractions(email);
        verify(mapper, never()).updateTcmAppointment(any());
    }
}
