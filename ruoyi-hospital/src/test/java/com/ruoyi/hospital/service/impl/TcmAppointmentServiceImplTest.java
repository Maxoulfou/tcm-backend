package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.domain.TcmAppointment;
import com.ruoyi.hospital.domain.TcmClinicSetting;
import com.ruoyi.hospital.domain.TcmRoom;
import com.ruoyi.hospital.domain.TcmServiceType;
import com.ruoyi.hospital.mapper.TcmAppointmentMapper;
import com.ruoyi.hospital.mapper.TcmClinicSettingMapper;
import com.ruoyi.hospital.mapper.TcmRoomMapper;
import com.ruoyi.hospital.mapper.TcmServiceTypeMapper;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.mapper.SysUserMapper;

@ExtendWith(MockitoExtension.class)
class TcmAppointmentServiceImplTest
{
    @Mock
    private TcmAppointmentMapper appointmentMapper;

    @Mock
    private TcmClinicSettingMapper settingMapper;

    @Mock
    private TcmRoomMapper roomMapper;

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private TcmServiceTypeMapper serviceTypeMapper;

    private TcmAppointmentServiceImpl service;

    @BeforeEach
    void setUp()
    {
        service = new TcmAppointmentServiceImpl();
        ReflectionTestUtils.setField(service, "appointmentMapper", appointmentMapper);
        ReflectionTestUtils.setField(service, "settingMapper", settingMapper);
        ReflectionTestUtils.setField(service, "roomMapper", roomMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "serviceTypeMapper", serviceTypeMapper);

        lenient().when(userMapper.selectActiveUserIds()).thenReturn(Collections.emptyList());
        lenient().when(roomMapper.selectTcmRoomList(any())).thenReturn(Collections.emptyList());
        lenient().when(roomMapper.selectTcmRoomById(anyString())).thenReturn(null);
        lenient().when(appointmentMapper.selectAppointmentsInRange(any(), any(), anyString(), anyString(), any()))
                .thenReturn(null);
    }

    @Test
    void checkSlot_shouldAllowBackToBackWhenServiceUsesShortPractitionerWindow()
    {
        TcmServiceType serviceType = serviceType("short_practitioner", 60, false);
        serviceType.setPractitionerTime("20");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("short_practitioner")).thenReturn(serviceType);
        when(userMapper.selectUserById(101L)).thenReturn(practitioner(
                101L,
                "Dr Window",
                "{\"serviceKeys\":[\"short_practitioner\"],\"workingHours\":{\"wednesday\":[{\"start\":\"09:00\",\"end\":\"12:00\"}]}}"));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String practitionerId = invocation.getArgument(0);
                    if ("101".equals(practitionerId))
                    {
                        return Collections.singletonList(appointment("a-1", "101", "2026-04-08 10:00:00", "2026-04-08 11:00:00"));
                    }
                    return Collections.emptyList();
                });

        Map<String, Object> result = service.checkSlot(
                "101",
                null,
                "short_practitioner",
                "2026-04-08 10:20:00",
                "2026-04-08 11:20:00",
                null);

        assertTrue((Boolean) result.get("available"));
        assertEquals("101", result.get("assignedPractitionerId"));
    }

    @Test
    void checkSlot_shouldFallbackPractitionerBusyWindowToServiceDurationWhenPractitionerTimeMissing()
    {
        TcmServiceType serviceType = serviceType("full_duration_busy", 50, false);
        serviceType.setPractitionerTime(null);
        when(serviceTypeMapper.selectTcmServiceTypeByKey("full_duration_busy")).thenReturn(serviceType);
        when(userMapper.selectUserById(101L)).thenReturn(practitioner(
                101L,
                "Dr Full",
                "{\"serviceKeys\":[\"full_duration_busy\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"12:00\"}]}}"));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String practitionerId = invocation.getArgument(0);
                    if ("101".equals(practitionerId))
                    {
                        TcmAppointment existing = appointment("a-1", "101", "2026-04-06 09:30:00", "2026-04-06 10:20:00");
                        existing.setServiceType("full_duration_busy");
                        return Collections.singletonList(existing);
                    }
                    return Collections.emptyList();
                });

        Map<String, Object> blockedResult = service.checkSlot(
                "101",
                null,
                "full_duration_busy",
                "2026-04-06 09:50:00",
                "2026-04-06 10:40:00",
                null);

        assertFalse((Boolean) blockedResult.get("available"));
        assertTrue((Boolean) blockedResult.get("practitionerConflict"));
    }

    @Test
    void checkSlot_shouldRejectCrossMidnightAndReverseTimeRange()
    {
        Map<String, Object> crossMidnight = service.checkSlot(
                "101",
                null,
                null,
                "2026-04-06 23:30:00",
                "2026-04-07 00:00:00",
                null);

        assertFalse((Boolean) crossMidnight.get("available"));
        assertTrue((Boolean) crossMidnight.get("practitionerConflict"));
        @SuppressWarnings("unchecked")
        List<String> crossMidnightConflicts = (List<String>) crossMidnight.get("conflicts");
        assertTrue(crossMidnightConflicts.stream()
                .anyMatch(message -> message.contains("same day")));

        Map<String, Object> reverse = service.checkSlot(
                "101",
                null,
                null,
                "2026-04-06 10:30:00",
                "2026-04-06 10:00:00",
                null);

        assertFalse((Boolean) reverse.get("available"));
        assertTrue((Boolean) reverse.get("practitionerConflict"));
        @SuppressWarnings("unchecked")
        List<String> reverseConflicts = (List<String>) reverse.get("conflicts");
        assertTrue(reverseConflicts.stream()
                .anyMatch(message -> message.contains("after start time")));
    }

    @Test
    void insertTcmAppointment_shouldRejectCrossMidnightTimeRange()
    {
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType("acupuncture_new", 60));

        TcmAppointment appointment = appointment("a-1", null, "2026-04-06 23:30:00", "2026-04-07 00:00:00");
        appointment.setServiceType("acupuncture_new");

        ServiceException error = assertThrows(ServiceException.class, () -> service.insertTcmAppointment(appointment));

        assertEquals("appointment must start and end on the same day", error.getMessage());
    }

    @Test
    void updateTcmAppointment_shouldRejectReverseTimeRange()
    {
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType("acupuncture_new", 60));
        TcmAppointment existing = appointment("a-2", "101", "2026-04-06 09:00:00", "2026-04-06 10:00:00");
        existing.setServiceType("acupuncture_new");
        when(appointmentMapper.selectTcmAppointmentForUpdate("a-2")).thenReturn(existing);

        TcmAppointment updated = appointment("a-2", "101", "2026-04-06 10:30:00", "2026-04-06 10:00:00");
        updated.setServiceType("acupuncture_new");

        ServiceException error = assertThrows(ServiceException.class, () -> service.updateTcmAppointment(updated));

        assertEquals("appointment end time must be after start time", error.getMessage());
        verify(appointmentMapper, never()).updateTcmAppointment(any());
    }

    @Test
    void checkSlot_shouldRejectLongAppointmentPastClosingBoundary()
    {
        when(userMapper.selectUserById(101L)).thenReturn(practitioner(
                101L,
                "Dr Boundary",
                "{\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"17:30\"}]}}"));

        Map<String, Object> exactClose = service.checkSlot(
                "101",
                null,
                null,
                "2026-04-06 16:30:00",
                "2026-04-06 17:30:00",
                null);
        assertTrue((Boolean) exactClose.get("available"));

        Map<String, Object> pastClose = service.checkSlot(
                "101",
                null,
                null,
                "2026-04-06 17:00:00",
                "2026-04-06 18:00:00",
                null);

        assertFalse((Boolean) pastClose.get("available"));
        @SuppressWarnings("unchecked")
        List<String> conflicts = (List<String>) pastClose.get("conflicts");
        assertTrue(conflicts.stream().anyMatch(message -> message.contains("working hours")));
    }

    @Test
    void updateTcmAppointment_shouldSkipSlotCheckWhenOnlyMetadataChanges()
    {
        TcmAppointment existing = appointment("a-2", "p-2", "2026-03-28 09:00:00", "2026-03-28 09:30:00");
        existing.setRoomId("room-1");
        when(appointmentMapper.selectTcmAppointmentForUpdate("a-2")).thenReturn(existing);

        TcmAppointment updated = appointment("a-2", "p-2", "2026-03-28 09:00:00", "2026-03-28 09:30:00");
        updated.setRoomId("room-1");
        updated.setIntakeToken("new-token");

        when(appointmentMapper.updateTcmAppointment(updated)).thenReturn(1);

        int affected = service.updateTcmAppointment(updated);

        assertEquals(1, affected);
        verify(appointmentMapper).updateTcmAppointment(updated);
        verify(appointmentMapper, never()).selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any());
        verify(appointmentMapper, never()).selectTcmAppointmentList(any());
    }

    @Test
    void staleAppointmentFormCannotEraseOrReviveServerNotificationState()
    {
        TcmAppointment existing = appointment("notification-state", "p-2", "2026-03-28 09:00:00", "2026-03-28 09:30:00");
        existing.setPayload("{\"manageToken\":\"server-token\",\"aftercareEmailSentAt\":\"2026-03-28 10:30:00\","
                + "\"followUpEmailSentAtPendingToken\":\"server-claim\",\"followUpEmailSentAtPendingAt\":\"2026-03-31 09:30:00\","
                + "\"notificationOwnerInstance\":\"local\",\"changeEmailEvent\":\"server-change\",\"notes\":\"old\","
                + "\"notificationOutbox\":{\"followUpEmailSentAt\":{\"event\":\"current-event\"}},"
                + "\"aftercareEmailSentAtEvent\":\"delivered-event\"}");
        when(appointmentMapper.selectTcmAppointmentForUpdate(existing.getId())).thenReturn(existing);
        TcmAppointment updated = appointment(existing.getId(), "p-2", existing.getStartTime(), existing.getEndTime());
        updated.setPayload("{\"manageToken\":\"old-token\",\"aftercareEmailSentAt\":null,\"followUpEmailSentAt\":\"fake-sent\","
                + "\"reminderEmailSentAtPendingToken\":\"expired-claim\",\"changeEmailEvent\":\"old-change\",\"notes\":\"new\","
                + "\"notificationOutbox\":{},\"aftercareEmailSentAtEvent\":\"old-event\"}");
        when(appointmentMapper.updateTcmAppointment(updated)).thenReturn(1);
        service.updateTcmAppointment(updated);
        JSONObject saved = JSONObject.parseObject(updated.getPayload());
        assertEquals("server-token", saved.getString("manageToken"));
        assertEquals("2026-03-28 10:30:00", saved.getString("aftercareEmailSentAt"));
        assertEquals("server-claim", saved.getString("followUpEmailSentAtPendingToken"));
        assertEquals("2026-03-31 09:30:00", saved.getString("followUpEmailSentAtPendingAt"));
        assertEquals("local", saved.getString("notificationOwnerInstance"));
        assertEquals("server-change", saved.getString("changeEmailEvent"));
        assertEquals("delivered-event", saved.getString("aftercareEmailSentAtEvent"));
        assertEquals("current-event", saved.getJSONObject("notificationOutbox").getJSONObject("followUpEmailSentAt").getString("event"));
        assertFalse(saved.containsKey("followUpEmailSentAt"));
        assertFalse(saved.containsKey("reminderEmailSentAtPendingToken"));
        assertEquals("new", saved.getString("notes"));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(appointmentMapper);
        order.verify(appointmentMapper).selectTcmAppointmentForUpdate(existing.getId());
        order.verify(appointmentMapper).updateTcmAppointment(updated);
        verify(appointmentMapper, never()).selectTcmAppointmentById(anyString());
    }

    @Test
    void statusChangePreservesNotificationStateFromLockedCurrentRow()
    {
        TcmAppointment latest = appointment("latest-status", "p-2", "2026-03-28 09:00:00", "2026-03-28 09:30:00");
        String payload = "{\"aftercareEmailSentAt\":\"2026-03-28 10:30:00\","
                + "\"notificationOutbox\":{\"followUpEmailSentAt\":{\"event\":\"latest-event\"}},"
                + "\"followUpEmailSentAtPendingToken\":\"current-claim\"}";
        latest.setPayload(payload);
        when(appointmentMapper.selectTcmAppointmentForUpdate(latest.getId())).thenReturn(latest);

        TcmAppointment updated = service.updateStatus(latest.getId(), "confirmed");

        assertEquals("confirmed", updated.getStatus());
        assertEquals(payload, updated.getPayload());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(appointmentMapper);
        order.verify(appointmentMapper).selectTcmAppointmentForUpdate(latest.getId());
        order.verify(appointmentMapper).updateTcmAppointment(latest);
        verify(appointmentMapper, never()).selectTcmAppointmentById(anyString());
    }

    @Test
    void updatingMissingAppointmentStopsBeforeSchedulingOrWriting()
    {
        TcmAppointment missing = appointment("missing", "p-2", "2026-03-28 09:00:00", "2026-03-28 09:30:00");
        ServiceException error = assertThrows(ServiceException.class, () -> service.updateTcmAppointment(missing));
        assertEquals("appointment not found", error.getMessage());
        verify(appointmentMapper, never()).updateTcmAppointment(any());
        org.mockito.Mockito.verifyNoInteractions(serviceTypeMapper, roomMapper, settingMapper, userMapper);
    }

    @Test
    void completedIntakeConsumesOnlyTheCurrentTokenAndPreservesNotificationPayload()
    {
        TcmAppointment latest = appointment("intake", "p-2", "2026-03-28 09:00:00", "2026-03-28 09:30:00");
        latest.setIntakeToken("current-token"); latest.setIntakeSubmitted(0);
        latest.setPayload("{\"aftercareEmailSentAt\":\"delivered\"}");
        when(appointmentMapper.selectTcmAppointmentForUpdate("intake")).thenReturn(latest);
        when(appointmentMapper.updateNotificationIntake(any(), eq(latest))).thenReturn(1);
        when(appointmentMapper.updateTcmAppointment(latest)).thenReturn(1);

        TcmAppointment result = service.completeIntake("intake", "current-token", Map.of("chiefComplaint", "Pain"));

        assertEquals(null, result.getIntakeToken());
        assertEquals(1, result.getIntakeSubmitted());
        JSONObject payload = JSONObject.parseObject(result.getPayload());
        assertEquals("delivered", payload.getString("aftercareEmailSentAt"));
        assertEquals("Pain", payload.getJSONObject("intakeFormData").getString("chiefComplaint"));
        verify(appointmentMapper).updateNotificationIntake(argThat(row -> row.getIntakeToken() == null
                && Integer.valueOf(1).equals(row.getIntakeSubmitted())), any());
    }

    @Test
    void intakeSubmissionCannotConsumeARegeneratedToken()
    {
        TcmAppointment latest = appointment("intake", "p-2", "2026-03-28 09:00:00", "2026-03-28 09:30:00");
        latest.setIntakeToken("new-token");
        when(appointmentMapper.selectTcmAppointmentForUpdate("intake")).thenReturn(latest);
        assertThrows(ServiceException.class, () -> service.completeIntake("intake", "old-token", Map.of("chiefComplaint", "Pain")));
        verify(appointmentMapper, never()).updateNotificationIntake(any(), any());
        verify(appointmentMapper, never()).updateTcmAppointment(any());
    }

    @Test
    void newlyCreatedAppointmentCannotInjectNotificationQueueOrDeliveryMetadata()
    {
        TcmAppointment incoming = appointment("new", "p-2", "2026-03-28 09:00:00", "2026-03-28 09:30:00");
        incoming.setServiceType("time_block");
        incoming.setPayload("{\"notes\":\"Keep\",\"notificationOutbox\":{\"fake\":{}},\"aftercareEmailSentAtEvent\":\"forged\",\"manageToken\":\"forged\"}");
        when(appointmentMapper.insertTcmAppointment(incoming)).thenReturn(1);
        assertEquals(1, service.insertTcmAppointment(incoming));
        JSONObject payload = JSONObject.parseObject(incoming.getPayload());
        assertEquals("Keep", payload.getString("notes"));
        assertFalse(payload.containsKey("notificationOutbox"));
        assertFalse(payload.containsKey("aftercareEmailSentAtEvent"));
        assertFalse(payload.containsKey("manageToken"));
    }

    @Test
    void getAvailability_shouldAggregatePractitionersByConfiguredSortOrder()
    {
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType("acupuncture_new", 60));
        when(userMapper.selectActiveUserIds()).thenReturn(Arrays.asList(11L, 12L));
        when(userMapper.selectUserById(11L)).thenReturn(practitioner(
                11L,
                "Dr A",
                "{\"practitionerSortOrder\":2,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"wednesday\":[{\"start\":\"09:00\",\"end\":\"10:30\"}]}}"));
        when(userMapper.selectUserById(12L)).thenReturn(practitioner(
                12L,
                "Dr B",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"wednesday\":[{\"start\":\"09:00\",\"end\":\"10:30\"}]}}"));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(Collections.emptyList());
        Map<String, Object> result = service.getAvailability("2026-04-08", "acupuncture_new", null, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) result.get("slots");
        assertFalse(slots.isEmpty());
        @SuppressWarnings("unchecked")
        List<String> practitionerIds = (List<String>) slots.get(0).get("availablePractitionerIds");
        assertEquals(Arrays.asList("12", "11"), practitionerIds);
        assertEquals("12", slots.get(0).get("assignedPractitionerId"));
    }

    @Test
    void getAvailability_shouldAggregatePractitionersWhenNoPractitionerIsSelected()
    {
        TcmServiceType serviceType = serviceType("acupuncture_new", 30);
        serviceType.setPractitionerTime("20");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType);
        when(userMapper.selectActiveUserIds()).thenReturn(Arrays.asList(21L, 22L));
        when(userMapper.selectUserById(21L)).thenReturn(practitioner(
                21L,
                "Dr A",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"wednesday\":[{\"start\":\"09:00\",\"end\":\"11:00\"}]}}"));
        when(userMapper.selectUserById(22L)).thenReturn(practitioner(
                22L,
                "Dr B",
                "{\"practitionerSortOrder\":2,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"wednesday\":[{\"start\":\"09:00\",\"end\":\"11:00\"}]}}"));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String practitionerId = invocation.getArgument(0);
                    String startTime = invocation.getArgument(2);
                    String endTime = invocation.getArgument(3);
                    if ("21".equals(practitionerId)
                            && "2026-04-08 10:00:00".compareTo(endTime) < 0
                            && "2026-04-08 10:30:00".compareTo(startTime) > 0)
                    {
                        return Arrays.asList(appointment("a-occupied", "21", "2026-04-08 10:00:00", "2026-04-08 10:30:00"));
                    }
                    return Collections.emptyList();
                });

        Map<String, Object> result = service.getAvailability("2026-04-08", "acupuncture_new", null, null, null);

        assertEquals(20, result.get("slotStepMinutes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) result.get("slots");
        assertFalse(slots.isEmpty());

        Map<String, Object> nineSlot = slots.stream()
                .filter(slot -> "09:00".equals(slot.get("label")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> nineAvailableIds = (List<String>) nineSlot.get("availablePractitionerIds");
        assertEquals(Arrays.asList("21", "22"), nineAvailableIds);
        assertEquals("21", nineSlot.get("assignedPractitionerId"));
    }

    @Test
    void insertTcmAppointment_shouldAutoAssignPractitionerWhenMissing()
    {
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType("acupuncture_new", 60));
        when(userMapper.selectActiveUserIds()).thenReturn(Arrays.asList(21L));
        when(userMapper.selectUserById(21L)).thenReturn(practitioner(
                21L,
                "Dr C",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"wednesday\":[{\"start\":\"09:00\",\"end\":\"12:00\"}]}}"));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(Collections.emptyList());
        when(appointmentMapper.insertTcmAppointment(any())).thenReturn(1);

        TcmAppointment appointment = appointment("a-3", null, "2026-04-08 09:00:00", "2026-04-08 10:00:00");
        appointment.setServiceType("acupuncture_new");

        int affected = service.insertTcmAppointment(appointment);

        assertEquals(1, affected);
        assertEquals("21", appointment.getPractitionerId());
        verify(appointmentMapper).insertTcmAppointment(argThat(saved ->
                "21".equals(saved.getPractitionerId()) && "acupuncture_new".equals(saved.getServiceType())));
    }

    @Test
    void getAvailability_shouldUseServiceDurationStepWhenOnlyOneRoomMatches()
    {
        TcmServiceType serviceType = new TcmServiceType();
        serviceType.setServiceKey("tagged_service");
        serviceType.setDuration(60);
        serviceType.setPractitionerTime("20");
        serviceType.setRoomRequired(1);
        serviceType.setRequiredTag("acupuncture");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("tagged_service")).thenReturn(serviceType);
        lenient().when(settingMapper.selectSettingByKey("practitionerInterval")).thenReturn(setting("practitionerInterval", "20"));
        when(userMapper.selectUserById(31L)).thenReturn(practitioner(
                31L,
                "Dr Tag",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"tagged_service\"],\"workingHours\":{\"monday\":[{\"start\":\"14:20\",\"end\":\"16:20\"}]}}"));

        TcmRoom supportRoom = new TcmRoom();
        supportRoom.setId("room-1");
        supportRoom.setName("Room A");
        supportRoom.setSupportTags("[\"acupuncture\"]");
        supportRoom.setIsActive(1);
        TcmRoom unsupportedRoom = new TcmRoom();
        unsupportedRoom.setId("room-2");
        unsupportedRoom.setName("Room B");
        unsupportedRoom.setSupportTags("[\"tuina\"]");
        unsupportedRoom.setIsActive(1);
        when(roomMapper.selectTcmRoomList(any())).thenReturn(Arrays.asList(supportRoom, unsupportedRoom));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = service.getAvailability("2026-04-06", "tagged_service", "31", null, null);

        assertEquals(60, result.get("slotStepMinutes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) result.get("slots");
        assertFalse(slots.stream().anyMatch(item -> "14:30".equals(item.get("label"))));
        Map<String, Object> slot = slots.stream()
                .filter(item -> "14:20".equals(item.get("label")))
                .findFirst()
                .orElseThrow();
        assertEquals("room-1", slot.get("roomId"));
        assertEquals("31", slot.get("assignedPractitionerId"));
        @SuppressWarnings("unchecked")
        List<String> availablePractitionerIds = (List<String>) slot.get("availablePractitionerIds");
        assertEquals(Collections.singletonList("31"), availablePractitionerIds);
    }

    @Test
    void getAvailability_shouldUsePractitionerOverlapStepWhenMultipleRoomsMatch()
    {
        TcmServiceType newVisit = serviceType("acupuncture_new", 60, true);
        newVisit.setPractitionerTime("overlap1");
        newVisit.setRequiredTag("acupuncture");
        TcmServiceType followUp = serviceType("acupuncture_followup", 30, true);
        followUp.setPractitionerTime("overlap2");
        followUp.setRequiredTag("acupuncture");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(newVisit);
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_followup")).thenReturn(followUp);
        when(userMapper.selectUserById(51L)).thenReturn(practitioner(
                51L,
                "Dr Overlap",
                "{\"serviceKeys\":[\"acupuncture_new\",\"acupuncture_followup\"],\"overlap1\":30,\"overlap2\":15,\"workingHours\":{\"monday\":[{\"start\":\"21:00\",\"end\":\"23:00\"}]}}"));
        when(roomMapper.selectTcmRoomList(any())).thenReturn(Arrays.asList(
                room("room-1", "Room 1", "[\"acupuncture\"]"),
                room("room-2", "Room 2", "[\"acupuncture\"]")));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> newVisitResult = service.getAvailability(
                "2026-04-06", "acupuncture_new", "51", null, null);
        assertEquals(30, newVisitResult.get("slotStepMinutes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> newVisitSlots = (List<Map<String, Object>>) newVisitResult.get("slots");
        assertTrue(newVisitSlots.stream().anyMatch(slot -> "21:30".equals(slot.get("label"))));
        assertFalse(newVisitSlots.stream().anyMatch(slot -> "21:15".equals(slot.get("label"))));

        Map<String, Object> followUpResult = service.getAvailability(
                "2026-04-06", "acupuncture_followup", "51", null, null);
        assertEquals(15, followUpResult.get("slotStepMinutes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> followUpSlots = (List<Map<String, Object>>) followUpResult.get("slots");
        assertTrue(followUpSlots.stream().anyMatch(slot -> "21:15".equals(slot.get("label"))));
    }

    @Test
    void getAvailability_shouldKeepEachPractitionerOverlapInAggregatedView()
    {
        TcmServiceType serviceType = serviceType("acupuncture_new", 60, true);
        serviceType.setPractitionerTime("overlap1");
        serviceType.setRequiredTag("acupuncture");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType);
        when(userMapper.selectActiveUserIds()).thenReturn(Arrays.asList(61L, 62L));
        when(userMapper.selectUserById(61L)).thenReturn(practitioner(
                61L,
                "Dr Thirty",
                "{\"serviceKeys\":[\"acupuncture_new\"],\"overlap1\":30,\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"11:00\"}]}}"));
        when(userMapper.selectUserById(62L)).thenReturn(practitioner(
                62L,
                "Dr Twenty",
                "{\"serviceKeys\":[\"acupuncture_new\"],\"overlap1\":20,\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"11:00\"}]}}"));
        when(roomMapper.selectTcmRoomList(any())).thenReturn(Arrays.asList(
                room("room-1", "Room 1", "[\"acupuncture\"]"),
                room("room-2", "Room 2", "[\"acupuncture\"]")));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = service.getAvailability("2026-04-06", "acupuncture_new", null, null, null);

        assertEquals(10, result.get("slotStepMinutes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) result.get("slots");
        Map<String, Object> nineTwenty = slots.stream()
                .filter(slot -> "09:20".equals(slot.get("label")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> nineTwentyIds = (List<String>) nineTwenty.get("availablePractitionerIds");
        assertEquals(Collections.singletonList("62"), nineTwentyIds);

        Map<String, Object> nineThirty = slots.stream()
                .filter(slot -> "09:30".equals(slot.get("label")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> nineThirtyIds = (List<String>) nineThirty.get("availablePractitionerIds");
        assertEquals(Collections.singletonList("61"), nineThirtyIds);
        assertFalse(slots.stream().anyMatch(slot -> "09:10".equals(slot.get("label"))));
    }
    @Test
    void getAvailability_shouldAllowOverlapReuseWithAnotherRoomAfterPractitionerWindowEnds()
    {
        TcmServiceType serviceType = serviceType("acupuncture_40", 50, true);
        serviceType.setPractitionerTime("20");
        serviceType.setRequiredTag("acupuncture");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_40")).thenReturn(serviceType);
        lenient().when(settingMapper.selectSettingByKey("practitionerInterval")).thenReturn(setting("practitionerInterval", "20"));
        when(userMapper.selectUserById(102L)).thenReturn(practitioner(
                102L,
                "Dr Reuse",
                "{\"serviceKeys\":[\"acupuncture_40\"],\"workingHours\":{\"monday\":[{\"start\":\"09:10\",\"end\":\"12:00\"}]}}"));
        when(roomMapper.selectTcmRoomList(any())).thenReturn(Arrays.asList(
                room("room-1", "Room 1", "[\"acupuncture\"]"),
                room("room-2", "Room 2", "[\"acupuncture\"]")));
        when(appointmentMapper.selectOverlappingAppointments(any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String practitionerId = invocation.getArgument(0);
                    String roomId = invocation.getArgument(1);
                    String startTime = invocation.getArgument(2);
                    String endTime = invocation.getArgument(3);
                    if ("102".equals(practitionerId)
                            && "2026-04-06 10:10:00".compareTo(startTime) > 0
                            && "2026-04-06 09:30:00".compareTo(endTime) < 0)
                    {
                        TcmAppointment overlap = appointment("existing-practitioner", "102", "2026-04-06 09:30:00", "2026-04-06 10:20:00");
                        overlap.setServiceType("acupuncture_40");
                        overlap.setRoomId("room-1");
                        return Collections.singletonList(overlap);
                    }
                    if ("room-1".equals(roomId)
                            && "2026-04-06 10:50:00".compareTo(startTime) > 0
                            && "2026-04-06 09:30:00".compareTo(endTime) < 0)
                    {
                        TcmAppointment overlap = appointment("existing-room", "102", "2026-04-06 09:30:00", "2026-04-06 10:20:00");
                        overlap.setServiceType("acupuncture_40");
                        overlap.setRoomId("room-1");
                        return Collections.singletonList(overlap);
                    }
                    return Collections.emptyList();
                });

        Map<String, Object> result = service.getAvailability("2026-04-06", "acupuncture_40", "102", null, null);

        assertEquals(20, result.get("slotStepMinutes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) result.get("slots");
        Map<String, Object> slot = slots.stream()
                .filter(item -> "09:50".equals(item.get("label")))
                .findFirst()
                .orElseThrow();
        assertEquals("room-2", slot.get("roomId"));
        assertEquals("102", slot.get("assignedPractitionerId"));
    }

    @Test
    void getAvailability_shouldRequireFullServiceWithinWorkingHoursButAllowRestOverlapInAnotherRoom()
    {
        TcmServiceType serviceType = serviceType("acupuncture_new", 60, true);
        serviceType.setPractitionerTime("overlap1");
        serviceType.setRequiredTag("acupuncture");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType);
        when(userMapper.selectUserById(201L)).thenReturn(practitioner(
                201L,
                "Dr Evening",
                "{\"serviceKeys\":[\"acupuncture_new\"],\"overlap1\":30,\"workingHours\":{\"monday\":[{\"start\":\"16:00\",\"end\":\"20:00\"}]}}"));
        when(roomMapper.selectTcmRoomList(any())).thenReturn(Arrays.asList(
                room("room-1", "Room 1", "[\"acupuncture\"]"),
                room("room-2", "Room 2", "[\"acupuncture\"]")));
        TcmAppointment existing = appointment("existing", "201", "2026-04-06 19:00:00", "2026-04-06 20:00:00");
        existing.setServiceType("acupuncture_new");
        existing.setRoomId("room-1");
        when(appointmentMapper.selectAppointmentsInRange(any(), any(), anyString(), anyString(), any()))
                .thenReturn(Collections.singletonList(existing));

        Map<String, Object> result = service.getAvailability("2026-04-06", "acupuncture_new", "201", null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) result.get("slots");
        Map<String, Object> sixThirty = slots.stream()
                .filter(slot -> "18:30".equals(slot.get("label")))
                .findFirst()
                .orElseThrow();
        assertEquals("room-2", sixThirty.get("roomId"));
        assertFalse(slots.stream().anyMatch(slot -> "19:30".equals(slot.get("label"))));
    }

    @Test
    void checkSlot_shouldFallbackPractitionerBusyMinutesToServiceDurationWhenUnset()
    {
        TcmServiceType serviceType = serviceType("herbal_followup", 40, false);
        serviceType.setPractitionerTime(null);
        when(serviceTypeMapper.selectTcmServiceTypeByKey("herbal_followup")).thenReturn(serviceType);
        when(userMapper.selectUserById(101L)).thenReturn(practitioner(
                101L,
                "李医师",
                "{\"serviceKeys\":[\"herbal_followup\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"12:00\"}]}}"));
        when(appointmentMapper.selectOverlappingAppointments(any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String practitionerId = invocation.getArgument(0);
                    if (!"101".equals(practitionerId))
                    {
                        return Collections.emptyList();
                    }
                    TcmAppointment overlap = appointment("existing", "101", "2026-04-06 09:00:00", "2026-04-06 09:40:00");
                    overlap.setServiceType("herbal_followup");
                    return Collections.singletonList(overlap);
                });

        Map<String, Object> result = service.checkSlot(
                "101",
                null,
                "herbal_followup",
                "2026-04-06 09:20:00",
                "2026-04-06 10:00:00",
                null);

        assertFalse((Boolean) result.get("available"));
        assertTrue((Boolean) result.get("practitionerConflict"));
    }

    @Test
    void checkSlot_shouldKeepRoomConflictOnFullServiceDuration()
    {
        TcmServiceType serviceType = serviceType("tagged_service", 60, true);
        serviceType.setPractitionerTime("20");
        serviceType.setRequiredTag("acupuncture");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("tagged_service")).thenReturn(serviceType);
        when(userMapper.selectUserById(31L)).thenReturn(practitioner(
                31L,
                "Dr Room",
                "{\"serviceKeys\":[\"tagged_service\"],\"workingHours\":{\"monday\":[{\"start\":\"14:20\",\"end\":\"16:20\"}]}}"));

        TcmRoom room = new TcmRoom();
        room.setId("room-1");
        room.setName("Room A");
        room.setSupportTags("[\"acupuncture\"]");
        room.setIsActive(1);
        lenient().when(roomMapper.selectTcmRoomById("room-1")).thenReturn(room);
        when(appointmentMapper.selectOverlappingAppointments(any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String roomId = invocation.getArgument(1);
                    String startTime = invocation.getArgument(2);
                    String endTime = invocation.getArgument(3);
                    if ("room-1".equals(roomId)
                            && "2026-04-06 14:20:00".equals(startTime)
                            && "2026-04-06 15:20:00".equals(endTime))
                    {
                        TcmAppointment overlap = appointment("room-conflict", "999", "2026-04-06 14:45:00", "2026-04-06 15:00:00");
                        overlap.setRoomId("room-1");
                        return Collections.singletonList(overlap);
                    }
                    return Collections.emptyList();
                });

        Map<String, Object> result = service.checkSlot(
                "31",
                "room-1",
                "tagged_service",
                "2026-04-06 14:20:00",
                "2026-04-06 15:20:00",
                null);

        assertFalse((Boolean) result.get("available"));
        assertTrue((Boolean) result.get("roomConflict"));
        @SuppressWarnings("unchecked")
        List<String> conflicts = (List<String>) result.get("conflicts");
        assertTrue(conflicts.stream().anyMatch(message -> message.contains("Room time conflict")));
    }

    @Test
    void checkSlot_shouldKeepOverlapWindowWhenPreferredRoomIsOneOfMultipleRooms()
    {
        TcmServiceType serviceType = serviceType("acupuncture_new", 60, true);
        serviceType.setPractitionerTime("overlap1");
        serviceType.setRequiredTag("acupuncture");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType);
        when(userMapper.selectUserById(201L)).thenReturn(practitioner(
                201L,
                "Dr Multi Room",
                "{\"serviceKeys\":[\"acupuncture_new\"],\"overlap1\":30,\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"12:00\"}]}}"));

        TcmRoom room1 = room("room-1", "Room 1", "[\"acupuncture\"]");
        TcmRoom room2 = room("room-2", "Room 2", "[\"acupuncture\"]");
        when(roomMapper.selectTcmRoomList(any())).thenReturn(Arrays.asList(room1, room2));
        when(roomMapper.selectTcmRoomById("room-2")).thenReturn(room2);

        TcmAppointment existing = appointment("existing", "201", "2026-04-06 10:00:00", "2026-04-06 11:00:00");
        existing.setServiceType("acupuncture_new");
        existing.setRoomId("room-1");
        when(appointmentMapper.selectOverlappingAppointments(any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String practitionerId = invocation.getArgument(0);
                    String roomId = invocation.getArgument(1);
                    if ("201".equals(practitionerId))
                    {
                        return Collections.singletonList(existing);
                    }
                    if ("room-1".equals(roomId))
                    {
                        return Collections.singletonList(existing);
                    }
                    return Collections.emptyList();
                });

        Map<String, Object> result = service.checkSlot(
                "201",
                "room-2",
                "acupuncture_new",
                "2026-04-06 09:30:00",
                "2026-04-06 10:30:00",
                null);

        assertTrue((Boolean) result.get("available"));
        assertFalse((Boolean) result.get("practitionerConflict"));
        assertFalse((Boolean) result.get("roomConflict"));
        assertEquals("room-2", result.get("assignedRoomId"));
    }

    @Test
    void getAvailability_shouldReturnEmptySlotsWhenNoRoomMatches()
    {
        TcmServiceType roomService = serviceType("room_service", 60, true);
        roomService.setPractitionerTime("20");
        roomService.setRequiredTag("acupuncture");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("room_service")).thenReturn(roomService);
        when(userMapper.selectActiveUserIds()).thenReturn(Collections.singletonList(41L));
        when(userMapper.selectUserById(41L)).thenReturn(practitioner(
                41L,
                "Dr No Room",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"room_service\"],\"workingHours\":{\"wednesday\":[{\"start\":\"09:00\",\"end\":\"12:00\"}]}}"));
        when(roomMapper.selectTcmRoomList(any())).thenReturn(Collections.singletonList(room("room-2", "Room B", "[\"tuina\"]")));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = service.getAvailability("2026-04-08", "room_service", null, null, null);

        assertEquals(20, result.get("slotStepMinutes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) result.get("slots");
        assertTrue(slots.isEmpty());
    }

    @Test
    void getWeeklySchedule_shouldReturnAnonymousSlotStatesForTheWeek()
    {
        TcmServiceType serviceType = serviceType("acupuncture_new", 30);
        serviceType.setPractitionerTime("20");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType);
        when(userMapper.selectUserById(21L)).thenReturn(practitioner(
                21L,
                "Dr C",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"11:00\"}]}}"));
        when(appointmentMapper.selectAppointmentsInRange(any(), any(), anyString(), anyString(), any()))
                .thenReturn(Arrays.asList(appointment("a-occupied", "21", "2026-04-06 10:00:00", "2026-04-06 10:30:00")));

        Map<String, Object> result = service.getWeeklySchedule("2026-04-06", "acupuncture_new", "21", null);

        assertEquals("2026-04-06", result.get("weekStart"));
        assertEquals("2026-04-12", result.get("weekEnd"));
        assertEquals(20, result.get("slotStepMinutes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) result.get("days");
        assertEquals(7, days.size());

        Map<String, Object> monday = days.get(0);
        assertEquals("2026-04-06", monday.get("date"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) monday.get("slots");
        assertTrue(slots.size() >= 72);

        Map<String, Object> bookableSlot = slots.stream()
                .filter(slot -> "09:00".equals(slot.get("time")))
                .findFirst()
                .orElseThrow();
        assertEquals("bookable", bookableSlot.get("state"));
        assertEquals("available", bookableSlot.get("status"));
        assertTrue((Boolean) bookableSlot.get("working"));
        assertTrue((Boolean) bookableSlot.get("available"));
        assertFalse((Boolean) bookableSlot.get("occupied"));

        Map<String, Object> occupiedSlot = slots.stream()
                .filter(slot -> "10:00".equals(slot.get("time")))
                .findFirst()
                .orElseThrow();
        assertEquals("occupied", occupiedSlot.get("state"));
        assertEquals("booked", occupiedSlot.get("status"));
        assertTrue((Boolean) occupiedSlot.get("working"));
        assertFalse((Boolean) occupiedSlot.get("available"));
        assertTrue((Boolean) occupiedSlot.get("occupied"));

        // 10:40 的整段预约(10:40-11:10, 时长30min)会超过 11:00 下班时间，
        // 因此医师仍在工作(working=true)但该时段不可预约(available=false)。
        Map<String, Object> workingOnlySlot = slots.stream()
                .filter(slot -> "10:40".equals(slot.get("time")))
                .findFirst()
                .orElseThrow();
        assertEquals("working", workingOnlySlot.get("state"));
        assertEquals("working", workingOnlySlot.get("status"));
        assertTrue((Boolean) workingOnlySlot.get("working"));
        assertFalse((Boolean) workingOnlySlot.get("available"));
        assertFalse((Boolean) workingOnlySlot.get("occupied"));
    }

    @Test
    void getWeeklySchedule_shouldNotExposeLateNightSlotsForSinglePractitioner()
    {
        TcmServiceType serviceType = serviceType("acupuncture_new", 30);
        serviceType.setPractitionerTime("20");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType);
        when(userMapper.selectUserById(101L)).thenReturn(practitioner(
                101L,
                "Dr Night",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"12:00\"},{\"start\":\"14:00\",\"end\":\"17:30\"}],\"tuesday\":[{\"start\":\"09:30\",\"end\":\"12:30\"}]}}"));
        Map<String, Object> result = service.getWeeklySchedule("2026-04-06", "acupuncture_new", "101", "room-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) result.get("days");
        Map<String, Object> monday = days.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) monday.get("slots");

        Map<String, Object> lateSlot = slots.stream()
                .filter(slot -> "23:40".equals(slot.get("time")))
                .findFirst()
                .orElseThrow();
        assertFalse((Boolean) lateSlot.get("working"));
        assertFalse((Boolean) lateSlot.get("available"));
        assertEquals("off", lateSlot.get("status"));
        assertEquals("off", lateSlot.get("state"));

        Map<String, Object> closingSlot = slots.stream()
                .filter(slot -> "17:40".equals(slot.get("time")))
                .findFirst()
                .orElseThrow();
        assertFalse((Boolean) closingSlot.get("working"));
        assertFalse((Boolean) closingSlot.get("available"));
        assertEquals("off", closingSlot.get("status"));
        assertEquals("off", closingSlot.get("state"));
    }

    @Test
    void getWeeklySchedule_shouldNotExposeLateNightSlotsInAggregatedView()
    {
        TcmServiceType serviceType = serviceType("acupuncture_new", 30);
        serviceType.setPractitionerTime("20");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType);
        when(userMapper.selectActiveUserIds()).thenReturn(Arrays.asList(101L, 102L));
        when(userMapper.selectUserById(101L)).thenReturn(practitioner(
                101L,
                "Dr A",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"12:00\"},{\"start\":\"14:00\",\"end\":\"17:30\"}]}}"));
        when(userMapper.selectUserById(102L)).thenReturn(practitioner(
                102L,
                "Dr B",
                "{\"practitionerSortOrder\":2,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"12:00\"},{\"start\":\"14:00\",\"end\":\"17:30\"}]}}"));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(Collections.emptyList());
        TcmRoom room = new TcmRoom();
        room.setId("room-1");
        room.setName("Room 1");
        room.setIsActive(1);
        when(roomMapper.selectTcmRoomById("room-1")).thenReturn(room);

        Map<String, Object> result = service.getWeeklySchedule("2026-04-06", "acupuncture_new", null, "room-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) result.get("days");
        Map<String, Object> monday = days.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) monday.get("slots");

        assertTrue(slots.stream().anyMatch(slot -> "09:00".equals(slot.get("time"))));
        assertFalse(slots.stream().anyMatch(slot -> "23:40".equals(slot.get("time"))));
    }

    @Test
    void getWeeklySchedule_shouldKeepUnsupportedSelectedRoomAsWorkingInsteadOfBooked()
    {
        TcmServiceType serviceType = serviceType("tagged_room_service", 60, true);
        serviceType.setRequiredTag("acupuncture");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("tagged_room_service")).thenReturn(serviceType);
        when(userMapper.selectUserById(101L)).thenReturn(practitioner(
                101L,
                "Dr Room",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"tagged_room_service\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"12:00\"}]}}"));
        when(roomMapper.selectTcmRoomById("room-2")).thenReturn(room("room-2", "Room 2", "[\"tuina\"]"));
        Map<String, Object> result = service.getWeeklySchedule("2026-04-06", "tagged_room_service", "101", "room-2");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) result.get("days");
        Map<String, Object> monday = days.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) monday.get("slots");
        Map<String, Object> nineSlot = slots.stream()
                .filter(slot -> "09:00".equals(slot.get("time")))
                .findFirst()
                .orElseThrow();

        assertEquals("working", nineSlot.get("status"));
        assertEquals("working", nineSlot.get("state"));
        assertFalse((Boolean) nineSlot.get("occupied"));
        assertFalse((Boolean) nineSlot.get("available"));
        assertTrue((Boolean) nineSlot.get("working"));
    }

    @Test
    void getWeeklySchedule_shouldAggregatePractitionersWhenNoPractitionerIsSelected()
    {
        TcmServiceType serviceType = serviceType("acupuncture_new", 30);
        serviceType.setPractitionerTime("20");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType);
        when(userMapper.selectActiveUserIds()).thenReturn(Arrays.asList(21L, 22L));
        when(userMapper.selectUserById(21L)).thenReturn(practitioner(
                21L,
                "Dr A",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"11:00\"}]}}"));
        when(userMapper.selectUserById(22L)).thenReturn(practitioner(
                22L,
                "Dr B",
                "{\"practitionerSortOrder\":2,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"11:00\"}]}}"));
        when(appointmentMapper.selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String practitionerId = invocation.getArgument(0);
                    String startTime = invocation.getArgument(2);
                    String endTime = invocation.getArgument(3);
                    if ("21".equals(practitionerId)
                            && "2026-04-06 10:00:00".compareTo(endTime) < 0
                            && "2026-04-06 10:30:00".compareTo(startTime) > 0)
                    {
                        return Arrays.asList(appointment("a-occupied", "21", "2026-04-06 10:00:00", "2026-04-06 10:30:00"));
                    }
                    return Collections.emptyList();
                });

        Map<String, Object> result = service.getWeeklySchedule("2026-04-06", "acupuncture_new", null, null);

        assertEquals(null, result.get("practitionerId"));
        assertEquals(20, result.get("slotStepMinutes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) result.get("days");
        Map<String, Object> monday = days.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) monday.get("slots");
        assertTrue(slots.stream().allMatch(slot -> "available".equals(slot.get("status"))));

        Map<String, Object> nineSlot = slots.stream()
                .filter(slot -> "09:00".equals(slot.get("time")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> nineAvailableIds = (List<String>) nineSlot.get("availablePractitionerIds");
        assertTrue(!nineAvailableIds.isEmpty());
        assertEquals(nineAvailableIds.size(), nineSlot.get("availableCount"));
        assertTrue(nineAvailableIds.contains(String.valueOf(nineSlot.get("assignedPractitionerId"))));
        assertEquals("available", nineSlot.get("status"));
        assertEquals("bookable", nineSlot.get("state"));

        Map<String, Object> tenSlot = slots.stream()
                .filter(slot -> "10:00".equals(slot.get("time")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> tenAvailableIds = (List<String>) tenSlot.get("availablePractitionerIds");
        assertTrue(!tenAvailableIds.isEmpty());
        assertEquals(tenAvailableIds.size(), tenSlot.get("availableCount"));
        assertTrue(tenAvailableIds.contains(String.valueOf(tenSlot.get("assignedPractitionerId"))));
        assertEquals("available", tenSlot.get("status"));
        assertEquals("bookable", tenSlot.get("state"));

        // 10:40 起整段预约会超过 11:00 下班时间，聚合视图不再释放该时段；
        // 最后一个能完整容纳 30min 预约的时段是 10:20。
        Map<String, Object> halfPastSlot = slots.stream()
                .filter(slot -> "10:20".equals(slot.get("time")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> halfPastIds = (List<String>) halfPastSlot.get("availablePractitionerIds");
        assertTrue(!halfPastIds.isEmpty());
        assertEquals(halfPastIds.size(), halfPastSlot.get("availableCount"));
        assertTrue(halfPastIds.contains(String.valueOf(halfPastSlot.get("assignedPractitionerId"))));
        assertEquals("available", halfPastSlot.get("status"));
        assertEquals("bookable", halfPastSlot.get("state"));

        assertFalse(slots.stream().anyMatch(slot -> "10:40".equals(slot.get("time"))));
    }

    @Test
    void getWeeklySchedule_shouldReusePreloadedAppointmentsForAggregatedView()
    {
        TcmServiceType serviceType = serviceType("acupuncture_new", 30);
        serviceType.setPractitionerTime("20");
        when(serviceTypeMapper.selectTcmServiceTypeByKey("acupuncture_new")).thenReturn(serviceType);
        when(userMapper.selectActiveUserIds()).thenReturn(Arrays.asList(21L, 22L));
        when(userMapper.selectUserById(21L)).thenReturn(practitioner(
                21L,
                "Dr A",
                "{\"practitionerSortOrder\":1,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"11:00\"}]}}"));
        when(userMapper.selectUserById(22L)).thenReturn(practitioner(
                22L,
                "Dr B",
                "{\"practitionerSortOrder\":2,\"serviceKeys\":[\"acupuncture_new\"],\"workingHours\":{\"monday\":[{\"start\":\"09:00\",\"end\":\"11:00\"}]}}"));
        TcmAppointment existing = appointment("a-occupied", "21", "2026-04-06 10:00:00", "2026-04-06 10:30:00");
        existing.setServiceType("acupuncture_new");
        when(appointmentMapper.selectAppointmentsInRange(any(), any(), anyString(), anyString(), any()))
                .thenReturn(Collections.singletonList(existing));

        Map<String, Object> result = service.getWeeklySchedule("2026-04-06", "acupuncture_new", null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) result.get("days");
        Map<String, Object> monday = days.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) monday.get("slots");
        Map<String, Object> tenSlot = slots.stream()
                .filter(slot -> "10:00".equals(slot.get("time")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> availableIds = (List<String>) tenSlot.get("availablePractitionerIds");
        assertEquals(Collections.singletonList("22"), availableIds);
        assertEquals("22", tenSlot.get("assignedPractitionerId"));
        verify(appointmentMapper, never()).selectOverlappingAppointments(anyString(), any(), anyString(), anyString(), any());
        verify(serviceTypeMapper, times(1)).selectTcmServiceTypeByKey("acupuncture_new");
        verify(userMapper, times(1)).selectUserById(21L);
        verify(userMapper, times(1)).selectUserById(22L);
    }

    private TcmAppointment appointment(String id, String practitionerId, String start, String end)
    {
        TcmAppointment appointment = new TcmAppointment();
        appointment.setId(id);
        appointment.setPractitionerId(practitionerId);
        appointment.setStartTime(start);
        appointment.setEndTime(end);
        appointment.setStatus("booked");
        return appointment;
    }

    private SysUser userWithId(Long userId)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        return user;
    }

    private SysUser practitioner(Long userId, String name, String remark)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setNickName(name);
        user.setRemark(remark);
        user.setStatus("0");
        SysRole role = new SysRole();
        role.setRoleId(userId);
        role.setRoleKey("practitioner");
        role.setFlag(true);
        user.setRoles(Collections.singletonList(role));
        return user;
    }

    private TcmClinicSetting setting(String key, String value)
    {
        TcmClinicSetting setting = new TcmClinicSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        return setting;
    }

    private TcmServiceType serviceType(String key, int duration)
    {
        return serviceType(key, duration, false);
    }

    private TcmServiceType serviceType(String key, int duration, boolean roomRequired)
    {
        TcmServiceType serviceType = new TcmServiceType();
        serviceType.setServiceKey(key);
        serviceType.setDuration(duration);
        serviceType.setRoomRequired(roomRequired ? 1 : 0);
        serviceType.setDefaultPrice(BigDecimal.TEN);
        return serviceType;
    }

    private TcmRoom room(String id, String name, String supportTags)
    {
        TcmRoom room = new TcmRoom();
        room.setId(id);
        room.setName(name);
        room.setSupportTags(supportTags);
        room.setIsActive(1);
        return room;
    }
}
