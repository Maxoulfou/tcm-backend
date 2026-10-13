package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.domain.TcmAppointment;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.mapper.TcmAppointmentMapper;
import com.ruoyi.hospital.mapper.TcmConsultationMapper;
import com.ruoyi.hospital.mapper.TcmConsultationModMapper;
import com.ruoyi.hospital.mapper.TcmPatientMapper;
import com.ruoyi.hospital.service.ITcmInventoryService;
import com.ruoyi.hospital.service.ITcmAppointmentNotificationService;
import com.ruoyi.hospital.service.ITcmPatientFileService;
import com.ruoyi.hospital.service.ITcmPdfService;
import com.ruoyi.hospital.utils.PayloadUtils;

@ExtendWith(MockitoExtension.class)
class TcmConsultationServiceImplTest
{
    @Mock
    private TcmConsultationMapper consultationMapper;

    @Mock
    private TcmConsultationModMapper modMapper;

    @Mock
    private ITcmPdfService pdfService;

    @Mock
    private TcmPatientMapper patientMapper;

    @Mock
    private ITcmPatientFileService patientFileService;

    @Mock
    private ITcmInventoryService inventoryService;

    @Mock
    private TcmAppointmentMapper appointmentMapper;

    @Mock
    private ITcmAppointmentNotificationService appointmentNotificationService;

    private TcmConsultationServiceImpl service;

    @BeforeEach
    void setUp()
    {
        service = new TcmConsultationServiceImpl();
        org.mockito.Mockito.lenient().when(consultationMapper.selectTcmConsultationForUpdate(anyString()))
                .thenAnswer(invocation -> consultationMapper.selectTcmConsultationById(invocation.getArgument(0)));
        ReflectionTestUtils.setField(service, "consultationMapper", consultationMapper);
        ReflectionTestUtils.setField(service, "modMapper", modMapper);
        ReflectionTestUtils.setField(service, "pdfService", pdfService);
        ReflectionTestUtils.setField(service, "patientMapper", patientMapper);
        ReflectionTestUtils.setField(service, "patientFileService", patientFileService);
        ReflectionTestUtils.setField(service, "inventoryService", inventoryService);
        ReflectionTestUtils.setField(service, "appointmentMapper", appointmentMapper);
        ReflectionTestUtils.setField(service, "appointmentNotificationService", appointmentNotificationService);
    }

    @Test
    void completeConsultation_shouldNotCompleteFutureAppointmentFromPayload()
    {
        JSONObject payload = new JSONObject();
        payload.put("appointmentId", "appointment-future");
        TcmConsultation existing = consultation("consult-link-future", payload);
        existing.setStatus("draft");
        TcmAppointment future = appointment("appointment-future", "2026-04-07 10:00:00", "doctor-1");
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(appointmentMapper.selectTcmAppointmentById(future.getId())).thenReturn(future);
        when(appointmentMapper.selectTcmAppointmentList(any(TcmAppointment.class))).thenReturn(Collections.singletonList(future));

        TcmConsultation result = service.completeConsultation(existing.getId(), "doctor-1");

        assertEquals("completed", result.getStatus());
        assertEquals("booked", future.getStatus());
        verify(appointmentMapper, never()).updateTcmAppointment(any());
        verify(appointmentNotificationService, never()).handleAppointmentStatusChanged(any(), any());
    }

    @Test
    void completeConsultation_shouldNotCompleteAnotherPractitionersAppointment()
    {
        JSONObject payload = new JSONObject();
        payload.put("sourceAppointmentId", "appointment-other-doctor");
        TcmConsultation existing = consultation("consult-link-doctor", payload);
        existing.setStatus("draft");
        TcmAppointment other = appointment("appointment-other-doctor", "2026-04-06 10:00:00", "doctor-2");
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(appointmentMapper.selectTcmAppointmentById(other.getId())).thenReturn(other);
        when(appointmentMapper.selectTcmAppointmentList(any(TcmAppointment.class))).thenReturn(Collections.singletonList(other));

        service.completeConsultation(existing.getId(), "doctor-1");

        assertEquals("booked", other.getStatus());
        verify(appointmentMapper, never()).updateTcmAppointment(any());
        verify(appointmentNotificationService, never()).handleAppointmentStatusChanged(any(), any());
    }

    @Test
    void completeConsultation_shouldFallbackToUniqueSameDayAppointmentWhenPayloadIdIsFuture()
    {
        JSONObject payload = new JSONObject();
        payload.put("latestIntakeAppointmentId", "appointment-future");
        TcmConsultation existing = consultation("consult-link-fallback", payload);
        existing.setStatus("draft");
        TcmAppointment future = appointment("appointment-future", "2026-04-07 10:00:00", "doctor-1");
        TcmAppointment sameDay = appointment("appointment-same-day", "2026-04-06 11:00:00", "doctor-1");
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(appointmentMapper.selectTcmAppointmentById(future.getId())).thenReturn(future);
        when(appointmentMapper.selectTcmAppointmentById(sameDay.getId())).thenReturn(sameDay);
        when(appointmentMapper.selectTcmAppointmentList(any(TcmAppointment.class))).thenReturn(List.of(future, sameDay));

        service.completeConsultation(existing.getId(), "doctor-1");

        assertEquals("booked", future.getStatus());
        assertEquals("completed", sameDay.getStatus());
        verify(appointmentMapper).updateTcmAppointment(sameDay);
        verify(appointmentMapper, never()).updateTcmAppointment(future);
        verify(appointmentNotificationService).handleAppointmentStatusChanged(any(TcmAppointment.class), eq(sameDay));
    }

    @Test
    void completeConsultation_shouldNotGuessAnAppointmentWithoutPractitioner()
    {
        JSONObject payload = new JSONObject();
        payload.put("appointmentId", "appointment-unassigned");
        TcmConsultation existing = consultation("consult-link-unassigned", payload);
        existing.setStatus("draft");
        TcmAppointment unassigned = appointment("appointment-unassigned", "2026-04-06 10:00:00", null);
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(appointmentMapper.selectTcmAppointmentById(unassigned.getId())).thenReturn(unassigned);
        when(appointmentMapper.selectTcmAppointmentList(any(TcmAppointment.class))).thenReturn(Collections.singletonList(unassigned));

        service.completeConsultation(existing.getId(), "doctor-1");

        assertEquals("booked", unassigned.getStatus());
        verify(appointmentMapper, never()).updateTcmAppointment(any());
        verify(appointmentNotificationService, never()).handleAppointmentStatusChanged(any(), any());
    }

    @Test
    void completeConsultation_shouldIgnoreCompletedCancelledAndUnknownAppointmentStatuses()
    {
        for (String status : List.of("completed", "cancelled", "unknown"))
        {
            JSONObject payload = new JSONObject();
            payload.put("appointmentId", "appointment-" + status);
            TcmConsultation existing = consultation("consult-link-" + status, payload);
            existing.setStatus("draft");
            TcmAppointment appointment = appointment("appointment-" + status, "2026-04-06 10:00:00", "doctor-1");
            appointment.setStatus(status);
            when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
            when(appointmentMapper.selectTcmAppointmentById(appointment.getId())).thenReturn(appointment);

            service.completeConsultation(existing.getId(), "doctor-1");

            assertEquals(status, appointment.getStatus());
        }
        verify(appointmentMapper, never()).updateTcmAppointment(any());
        verify(appointmentNotificationService, never()).handleAppointmentStatusChanged(any(), any());
    }

    @Test
    void completeConsultation_shouldUseValidExplicitAppointmentWithoutGuessingOtherSameDayAppointments()
    {
        JSONObject payload = new JSONObject();
        payload.put("appointmentId", "appointment-explicit");
        TcmConsultation existing = consultation("consult-link-explicit", payload);
        existing.setStatus("draft");
        TcmAppointment appointment = appointment("appointment-explicit", "2026-04-06 10:00:00", "doctor-1");
        appointment.setStatus("confirmed");
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(appointmentMapper.selectTcmAppointmentById(appointment.getId())).thenReturn(appointment);

        service.completeConsultation(existing.getId(), "doctor-1");

        assertEquals("completed", appointment.getStatus());
        verify(appointmentMapper).updateTcmAppointment(appointment);
        verify(appointmentMapper, never()).selectTcmAppointmentList(any(TcmAppointment.class));
        verify(appointmentNotificationService).handleAppointmentStatusChanged(any(TcmAppointment.class), eq(appointment));
    }

    @Test
    void syncPrescription_shouldKeepEmptyPrescriptionAndClearReservation()
    {
        TcmConsultation existing = consultation("consult-1", payloadWithPrescription(
                prescription("rx-1", items(item("黄芪", "10", "g", "inv-1", "supplier-a", "70")),
                        reservations(reservation("inv-1", "黄芪", "70", "supplier-a")),
                        "editing")));
        when(consultationMapper.selectTcmConsultationById("consult-1")).thenReturn(existing);
        when(consultationMapper.updateTcmConsultation(any(TcmConsultation.class))).thenReturn(1);
        when(inventoryService.restoreFromPrescription(anyList(), eq("raw_herbs"))).thenReturn(successResult());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prescription", prescription("rx-1", new ArrayList<>(), null, "editing"));

        TcmConsultation result = service.syncPrescription("consult-1", body, "u-1");

        JSONObject payload = JSON.parseObject(result.getPayload());
        JSONArray prescriptions = payload.getJSONArray("prescriptions");
        JSONObject updated = prescriptions.getJSONObject(0);
        assertEquals(0, updated.getJSONArray("items").size());
        assertEquals(0, updated.getJSONArray("inventoryReservation").size());
        verify(inventoryService).restoreFromPrescription(anyList(), eq("raw_herbs"));
        verify(inventoryService, never()).deductFromPrescription(anyList(), eq("raw_herbs"));
    }

    @Test
    void syncPrescription_shouldReuseExistingReservationWhenInventorySnapshotUnchanged()
    {
        TcmConsultation existing = consultation("consult-powder", payloadWithPrescription(
                prescription("rx-powder",
                        "powder",
                        items(item("人参", "10", "g", "inv-rs", "supplier-rs", "14")),
                        reservations(reservation("inv-rs", "人参", "14", "supplier-rs")),
                        "editing")));
        when(consultationMapper.selectTcmConsultationById("consult-powder")).thenReturn(existing);
        when(consultationMapper.updateTcmConsultation(any(TcmConsultation.class))).thenReturn(1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prescription", prescription(
                "rx-powder",
                "powder",
                items(item("人参", "10", "g", "inv-rs", "supplier-rs", "14")),
                null,
                "editing"));

        TcmConsultation result = service.syncPrescription("consult-powder", body, "u-powder");

        JSONObject payload = JSON.parseObject(result.getPayload());
        JSONObject updated = payload.getJSONArray("prescriptions").getJSONObject(0);
        assertEquals("14", updated.getJSONArray("inventoryReservation").getJSONObject(0).getString("reservedQty"));
        verify(inventoryService, never()).restoreFromPrescription(anyList(), eq("powder"));
        verify(inventoryService, never()).deductFromPrescription(anyList(), eq("powder"));
    }

    @Test
    void syncPrescription_shouldNotReserveInventoryForExternalPurchase()
    {
        TcmConsultation existing = consultation("consult-external", payloadWithPrescription(
                prescription("rx-external", new ArrayList<>(), null, "editing")));
        when(consultationMapper.selectTcmConsultationById("consult-external")).thenReturn(existing);
        when(consultationMapper.updateTcmConsultation(any(TcmConsultation.class))).thenReturn(1);

        Map<String, Object> rx = prescription(
                "rx-external",
                "raw_herbs",
                items(item("陈皮", "5", "g", "inv-cp", "supplier-cp", "35")),
                null,
                "editing");
        rx.put("whereToGet", "External 外部购买");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prescription", rx);

        TcmConsultation result = service.syncPrescription("consult-external", body, "u-external");

        JSONObject payload = JSON.parseObject(result.getPayload());
        JSONObject updated = payload.getJSONArray("prescriptions").getJSONObject(0);
        assertTrue(updated.getJSONArray("inventoryReservation").isEmpty());
        verify(inventoryService, never()).deductFromPrescription(anyList(), anyString());
        verify(inventoryService, never()).restoreFromPrescription(anyList(), anyString());
    }

    @Test
    void completePrescription_shouldNotDeductInventoryForExternalPurchase()
    {
        Map<String, Object> rx = prescription(
                "rx-external",
                "raw_herbs",
                items(item("陈皮", "5", "g", "inv-cp", "supplier-cp", "35")),
                null,
                "editing");
        rx.put("whereToGet", "External 外部购买");
        TcmConsultation existing = consultation("consult-external-complete", payloadWithPrescription(rx));
        when(consultationMapper.selectTcmConsultationById("consult-external-complete")).thenReturn(existing);
        when(consultationMapper.updateTcmConsultation(any(TcmConsultation.class))).thenReturn(1);

        TcmConsultation result = service.completePrescription(
                "consult-external-complete",
                "rx-external",
                Collections.emptyMap(),
                "u-external");

        JSONObject payload = JSON.parseObject(result.getPayload());
        JSONObject updated = payload.getJSONArray("prescriptions").getJSONObject(0);
        assertEquals("pending", updated.getString("rxStatus"));
        assertTrue(updated.getJSONArray("inventoryReservation").isEmpty());
        verify(inventoryService, never()).deductFromPrescription(anyList(), anyString());
    }

    @Test
    void completePrescription_retryAfterDispensingMustNotReopenOrReserveAgain()
    {
        TcmConsultation existing = consultation("consult-complete-retry", payloadWithPrescription(
                prescription("rx-complete-retry",
                        items(item("Test Herb", "5", "g", "inv-retry", null, "35")),
                        new ArrayList<>(), "editing")));
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(inventoryService.deductFromPrescription(anyList(), eq("raw_herbs")))
                .thenReturn(deductSuccess("inv-retry", "Test Herb", "35", null));

        service.completePrescription(existing.getId(), "rx-complete-retry", Collections.emptyMap(), "doctor-1");
        service.completePrescription(existing.getId(), "rx-complete-retry", Collections.emptyMap(), "doctor-1");
        service.dispensePrescription(existing.getId(), "rx-complete-retry", "pharmacist-1");
        JSONObject dispensed = JSON.parseObject(existing.getPayload()).getJSONArray("prescriptions").getJSONObject(0);
        String dispensedAt = dispensed.getString("dispensingCompletedAt");
        org.mockito.Mockito.clearInvocations(consultationMapper, modMapper);

        service.completePrescription(existing.getId(), "rx-complete-retry", Collections.emptyMap(), "doctor-1");
        TcmConsultation result = service.completePrescription(
                existing.getId(), "rx-complete-retry", Collections.emptyMap(), "doctor-1");

        JSONObject unchanged = JSON.parseObject(result.getPayload()).getJSONArray("prescriptions").getJSONObject(0);
        assertEquals("dispensed", unchanged.getString("rxStatus"));
        assertTrue(unchanged.getBooleanValue("dispensingCompleted"));
        assertEquals(dispensedAt, unchanged.getString("dispensingCompletedAt"));
        assertEquals("pharmacist-1", unchanged.getString("dispensedBy"));
        verify(inventoryService).deductFromPrescription(anyList(), eq("raw_herbs"));
        verify(consultationMapper, never()).updateTcmConsultation(any());
        verify(modMapper, never()).insertTcmConsultationMod(any());
    }

    @Test
    void updateTcmConsultation_shouldResyncInventoryWhenPrescriptionsChanged()
    {
        TcmConsultation existing = consultation("consult-2", payloadWithPrescription(
                prescription("rx-2", items(item("党参", "5", "g", "inv-1", "supplier-a", "35")),
                        reservations(reservation("inv-1", "党参", "35", "supplier-a")),
                        "editing")));
        existing.setStatus("draft");
        TcmConsultation incoming = consultation("consult-2", payloadWithPrescription(
                prescription("rx-2", items(item("白术", "4", "g", "inv-2", "supplier-b", "28")), null, "editing")));
        incoming.setStatus("draft");

        when(consultationMapper.selectTcmConsultationById("consult-2")).thenReturn(existing);
        when(consultationMapper.selectTcmConsultationList(any(TcmConsultation.class))).thenReturn(Collections.emptyList());
        when(consultationMapper.updateTcmConsultation(any(TcmConsultation.class))).thenReturn(1);
        when(inventoryService.restoreFromPrescription(anyList(), eq("raw_herbs"))).thenReturn(successResult());
        when(inventoryService.deductFromPrescription(anyList(), eq("raw_herbs")))
                .thenReturn(deductSuccess("inv-2", "白术", "28", "supplier-b"));

        int affected = service.updateTcmConsultation(incoming, "u-2");

        assertEquals(1, affected);
        verify(inventoryService).restoreFromPrescription(anyList(), eq("raw_herbs"));
        verify(inventoryService).deductFromPrescription(anyList(), eq("raw_herbs"));

        ArgumentCaptor<TcmConsultation> captor = ArgumentCaptor.forClass(TcmConsultation.class);
        verify(consultationMapper).updateTcmConsultation(captor.capture());
        JSONObject payload = JSON.parseObject(captor.getValue().getPayload());
        JSONObject updated = payload.getJSONArray("prescriptions").getJSONObject(0);
        assertEquals("inv-2", updated.getJSONArray("inventoryReservation").getJSONObject(0).getString("inventoryId"));
    }

    @Test
    void updateTcmConsultation_shouldDeductInventoryForFlattenedPutPayloadAfterEmptyPrescription()
    {
        JSONObject existingPayload = payloadWithPrescription(
                prescription("rx-4", new ArrayList<>(), new ArrayList<>(), "editing"));
        existingPayload.put("formulaName", "manual-rx");
        existingPayload.put("prescriptionType", "raw_herbs");
        existingPayload.put("differentiation", "manual verify");
        existingPayload.put("paidAmount", 0);
        existingPayload.put("outstandingAmount", 0);
        existingPayload.put("paymentStatus", "unpaid");
        existingPayload.put("dispensingCompleted", false);

        TcmConsultation existing = consultation("consult-4", existingPayload);
        existing.setStatus("draft");

        Map<String, Object> flattenedBody = PayloadUtils.flatten(existing);
        flattenedBody.put("createdAt", "2026-04-06T04:44:49+08:00");
        flattenedBody.put("totalAmount", 6);
        flattenedBody.put("totalWithoutTax", 6);
        flattenedBody.put("outstandingAmount", 6);
        flattenedBody.put("prescriptions", Collections.singletonList(prescription(
                "rx-4",
                items(item("白术", "6", "g", "inv-4", null, "6")),
                new ArrayList<>(),
                "editing")));
        flattenedBody.put("herbals", Collections.singletonList(herbal("白术", "6", "g", "inv-4", "6")));
        flattenedBody.put("formulaName", "manual-rx");
        flattenedBody.put("prescriptionType", "raw_herbs");

        TcmConsultation incoming = PayloadUtils.toConsultation(flattenedBody);
        incoming.setId("consult-4");

        when(consultationMapper.selectTcmConsultationById("consult-4")).thenReturn(existing);
        when(consultationMapper.selectTcmConsultationList(any(TcmConsultation.class))).thenReturn(Collections.emptyList());
        when(consultationMapper.updateTcmConsultation(any(TcmConsultation.class))).thenReturn(1);
        when(inventoryService.deductFromPrescription(anyList(), eq("raw_herbs")))
                .thenReturn(deductSuccess("inv-4", "白术", "6", null));

        int affected = service.updateTcmConsultation(incoming, "u-4");

        assertEquals(1, affected);
        verify(inventoryService).deductFromPrescription(anyList(), eq("raw_herbs"));

        ArgumentCaptor<TcmConsultation> captor = ArgumentCaptor.forClass(TcmConsultation.class);
        verify(consultationMapper).updateTcmConsultation(captor.capture());
        JSONObject payload = JSON.parseObject(captor.getValue().getPayload());
        JSONObject updated = payload.getJSONArray("prescriptions").getJSONObject(0);
        assertEquals("inv-4", updated.getJSONArray("inventoryReservation").getJSONObject(0).getString("inventoryId"));
    }

    @Test
    void updateTcmConsultation_shouldRejectCompletedRecordUntilReactivated()
    {
        TcmConsultation existing = consultation("consult-5", payloadWithPrescription(
                prescription("rx-5", items(item("黄芪", "6", "g", "inv-5", null, "42")), null, "pending")));
        existing.setStatus("completed");

        TcmConsultation incoming = consultation("consult-5", payloadWithPrescription(
                prescription("rx-5", items(item("黄芪", "6", "g", "inv-5", null, "42")), null, "pending")));
        incoming.setStatus("draft");

        when(consultationMapper.selectTcmConsultationById("consult-5")).thenReturn(existing);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updateTcmConsultation(incoming, "u-5"));

        assertTrue(error.getMessage().contains("Reactivate"));
        verify(consultationMapper, never()).updateTcmConsultation(any(TcmConsultation.class));
    }

    @Test
    void selectTcmConsultationById_shouldNotFabricateInventoryReservation()
    {
        TcmConsultation existing = consultation("consult-3", payloadWithPrescription(
                prescription("rx-3", items(item("茯苓", "6", "g", "inv-3", "supplier-c", "42")), null, "pending")));
        when(consultationMapper.selectTcmConsultationById("consult-3")).thenReturn(existing);

        TcmConsultation result = service.selectTcmConsultationById("consult-3");

        JSONObject payload = JSON.parseObject(result.getPayload());
        JSONObject updated = payload.getJSONArray("prescriptions").getJSONObject(0);
        assertTrue(updated.getJSONArray("inventoryReservation").isEmpty());
    }

    @Test
    void reactivateConsultation_shouldAcceptBusinessConsultationId()
    {
        TcmConsultation existing = consultation("db-reactivate", payloadWithPrescription(
                prescription("rx-reactivate", new ArrayList<>(), new ArrayList<>(), "editing")));
        existing.setConsultationId("ORD-ABC-123");
        existing.setStatus("completed");
        existing.setLockedAt("2026-05-26 10:00:00");

        when(consultationMapper.selectTcmConsultationById("ORD-ABC-123")).thenReturn(null);
        when(consultationMapper.selectTcmConsultationByConsultationId("ORD-ABC-123")).thenReturn(existing);
        when(consultationMapper.selectTcmConsultationById("db-reactivate")).thenReturn(existing);

        TcmConsultation result = service.reactivateConsultation("ORD-ABC-123", "u-reactivate");

        assertEquals("draft", result.getStatus());
        assertEquals("db-reactivate", result.getId());
        ArgumentCaptor<TcmConsultation> captor = ArgumentCaptor.forClass(TcmConsultation.class);
        verify(consultationMapper).reactivateTcmConsultation(captor.capture());
        assertEquals("db-reactivate", captor.getValue().getId());
    }

    @Test
    void softDeleteTcmConsultation_shouldRestoreInventoryAndClearReservation()
    {
        TcmConsultation existing = consultation("consult-delete", payloadWithPrescription(
                prescription("rx-delete",
                        items(item("黄芪", "6", "g", "inv-delete", "supplier-a", "42")),
                        reservations(reservation("inv-delete", "黄芪", "42", "supplier-a")),
                        "editing")));
        existing.setStatus("draft");

        when(consultationMapper.selectTcmConsultationById("consult-delete")).thenReturn(existing);
        when(inventoryService.restoreFromPrescription(anyList(), eq("raw_herbs"))).thenReturn(successResult());

        TcmConsultation result = service.softDeleteTcmConsultation("consult-delete");

        verify(inventoryService).restoreFromPrescription(anyList(), eq("raw_herbs"));
        assertTrue(result.getDeletedAt() != null && !result.getDeletedAt().isEmpty());
        JSONObject payload = JSON.parseObject(result.getPayload());
        JSONObject updated = payload.getJSONArray("prescriptions").getJSONObject(0);
        assertTrue(updated.getJSONArray("inventoryReservation").isEmpty());
    }

    @Test
    void permanentlyDeletePrescription_shouldAllowDeletedParentAndRestoreOnlyHeldStock()
    {
        Map<String, Object> rx = prescription("rx-trash",
                items(item("黄芪", "6", "g", "inv-trash", null, "42")),
                reservations(reservation("inv-trash", "黄芪", "42", null)), "editing");
        rx.put("deletedAt", "2026-09-01 10:00:00");
        rx.put("inventoryActionPending", true);
        TcmConsultation existing = consultation("consult-trash", payloadWithPrescription(rx));
        existing.setDeletedAt("2026-09-02 10:00:00");
        when(consultationMapper.selectTcmConsultationById("consult-trash")).thenReturn(existing);
        when(inventoryService.restoreFromPrescription(anyList(), eq("raw_herbs"))).thenReturn(successResult());

        TcmConsultation result = service.permanentlyDeletePrescription("consult-trash", "rx-trash", true, "admin");

        verify(consultationMapper).selectTcmConsultationForUpdate("consult-trash");
        assertEquals("2026-09-02 10:00:00", result.getDeletedAt());
        assertTrue(JSON.parseObject(result.getPayload()).getJSONArray("prescriptions").isEmpty());
        verify(inventoryService).restoreFromPrescription(anyList(), eq("raw_herbs"));
        assertThrows(ServiceException.class,
                () -> service.permanentlyDeletePrescription("consult-trash", "rx-trash", true, "admin"));
        verify(inventoryService).restoreFromPrescription(anyList(), eq("raw_herbs"));
    }

    @Test
    void permanentlyDeletePrescription_shouldStillRejectActivePrescriptionOnDeletedParent()
    {
        TcmConsultation existing = consultation("consult-trash", payloadWithPrescription(
                prescription("rx-active", new ArrayList<>(), null, "editing")));
        existing.setDeletedAt("2026-09-02 10:00:00");
        when(consultationMapper.selectTcmConsultationById("consult-trash")).thenReturn(existing);
        assertThrows(ServiceException.class,
                () -> service.permanentlyDeletePrescription("consult-trash", "rx-active", true, "admin"));
        verify(consultationMapper, never()).updateTcmConsultation(any());
    }

    @Test
    void restoreTcmConsultation_shouldRebuildReservation()
    {
        TcmConsultation existing = consultation("consult-restore", payloadWithPrescription(
                prescription("rx-restore",
                        items(item("人参", "2", "盒", "inv-pill", "supplier-pill", "2")),
                        new ArrayList<>(),
                        "editing")));
        existing.setStatus("draft");
        existing.setDeletedAt("2026-04-14 10:00:00");

        when(consultationMapper.selectTcmConsultationById("consult-restore")).thenReturn(existing);
        when(inventoryService.deductFromPrescription(anyList(), eq("raw_herbs")))
                .thenReturn(deductSuccess("inv-pill", "人参", "2", "supplier-pill"));

        TcmConsultation result = service.restoreTcmConsultation("consult-restore");

        verify(inventoryService).deductFromPrescription(anyList(), eq("raw_herbs"));
        assertEquals(null, result.getDeletedAt());
        JSONObject payload = JSON.parseObject(result.getPayload());
        JSONObject updated = payload.getJSONArray("prescriptions").getJSONObject(0);
        assertEquals("inv-pill", updated.getJSONArray("inventoryReservation").getJSONObject(0).getString("inventoryId"));
    }

    @Test
    void restoreTcmConsultation_shouldNotDeductAgainWhenRestoreIsRepeated()
    {
        TcmConsultation existing = consultation("consult-restore-retry", payloadWithPrescription(
                prescription("rx-restore-retry",
                        items(item("Test Herb", "5", "g", "inv-retry", null, "35")),
                        new ArrayList<>(),
                        "editing")));
        existing.setStatus("draft");
        existing.setDeletedAt("2026-04-14 10:00:00");
        when(consultationMapper.selectTcmConsultationById("consult-restore-retry")).thenReturn(existing);
        when(inventoryService.deductFromPrescription(anyList(), eq("raw_herbs")))
                .thenReturn(deductSuccess("inv-retry", "Test Herb", "35", null));

        service.restoreTcmConsultation("consult-restore-retry");
        TcmConsultation result = service.restoreTcmConsultation("consult-restore-retry");

        verify(consultationMapper, org.mockito.Mockito.times(2)).selectTcmConsultationForUpdate("consult-restore-retry");
        verify(inventoryService).deductFromPrescription(anyList(), eq("raw_herbs"));
        verify(consultationMapper).updateTcmConsultation(existing);
        assertEquals(null, result.getDeletedAt());
        JSONObject restored = JSON.parseObject(result.getPayload()).getJSONArray("prescriptions").getJSONObject(0);
        assertEquals("35", restored.getJSONArray("inventoryReservation").getJSONObject(0).getString("reservedQty"));
    }

    @Test
    void restoreTcmConsultation_shouldThrowWhenInventoryInsufficient()
    {
        TcmConsultation existing = consultation("consult-restore-fail", payloadWithPrescription(
                prescription("rx-restore-fail",
                        items(item("当归", "6", "g", "inv-fail", null, "42")),
                        new ArrayList<>(),
                        "editing")));
        existing.setStatus("draft");
        existing.setDeletedAt("2026-04-14 10:00:00");

        when(consultationMapper.selectTcmConsultationById("consult-restore-fail")).thenReturn(existing);
        when(inventoryService.deductFromPrescription(anyList(), eq("raw_herbs")))
                .thenReturn(deductFailure("当归库存不足"));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.restoreTcmConsultation("consult-restore-fail"));

        assertTrue(error.getMessage().contains("当归库存不足"));
    }

    @Test
    void deletionImmediatelyReleasesInventoryAndRepeatedDeletionDoesNotReleaseTwice()
    {
        TcmConsultation existing = consultation("delete-once", payloadWithPrescription(prescription("rx",
                items(item("黄芪", "10", "g", "stock", null, "70")),
                reservations(reservation("stock", "黄芪", "70", null)), "editing")));
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(inventoryService.restoreFromPrescription(anyList(), eq("raw_herbs"))).thenReturn(successResult());
        service.deletePrescription(existing.getId(), "rx", Map.of(), "qa");
        service.deletePrescription(existing.getId(), "rx", Map.of(), "qa");
        JSONObject deleted = JSON.parseObject(existing.getPayload()).getJSONArray("prescriptions").getJSONObject(0);
        assertTrue(deleted.getJSONArray("inventoryReservation").isEmpty());
        assertEquals(false, deleted.getBooleanValue("inventoryActionPending"));
        verify(inventoryService).restoreFromPrescription(anyList(), eq("raw_herbs"));
        assertThrows(ServiceException.class, () -> service.syncPrescription(existing.getId(),
                Map.of("prescription", prescription("rx", new ArrayList<>(), null, "editing")), "qa"));
    }

    @Test
    void savingUnchangedPrescriptionCannotEraseServerReservation()
    {
        TcmConsultation existing = consultation("metadata", payloadWithPrescription(prescription("rx",
                items(item("黄芪", "10", "g", "stock", null, "70")), reservations(reservation("stock", "黄芪", "70", null)), "editing")));
        existing.setStatus("draft");
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(consultationMapper.updateTcmConsultation(any())).thenReturn(1);
        TcmConsultation incoming = consultation(existing.getId(), payloadWithPrescription(prescription("rx",
                items(item("黄芪", "10", "g", "stock", null, "70")), new ArrayList<>(), "editing")));
        incoming.setStatus("draft");
        service.updateTcmConsultation(incoming, "qa");
        assertEquals("70", JSON.parseObject(incoming.getPayload()).getJSONArray("prescriptions").getJSONObject(0)
                .getJSONArray("inventoryReservation").getJSONObject(0).getString("reservedQty"));
        org.mockito.Mockito.verifyNoInteractions(inventoryService);
    }

    @Test
    void copiedPrescriptionIsReservedWhenParentIsCreatedAndIgnoresCopiedReservation()
    {
        TcmConsultation incoming = consultation("copy", payloadWithPrescription(prescription("rx",
                items(item("黄芪", "10", "g", "stock", null, "70")), reservations(reservation("old-stock", "黄芪", "700", null)), "editing")));
        incoming.setStatus("draft");
        when(consultationMapper.insertTcmConsultation(incoming)).thenReturn(1);
        when(inventoryService.deductFromPrescription(anyList(), eq("raw_herbs"))).thenReturn(deductSuccess("stock", "黄芪", "70", null));
        service.insertTcmConsultation(incoming);
        assertEquals("stock", JSON.parseObject(incoming.getPayload()).getJSONArray("prescriptions").getJSONObject(0)
                .getJSONArray("inventoryReservation").getJSONObject(0).getString("inventoryId"));
        verify(inventoryService, never()).restoreFromPrescription(anyList(), anyString());
        verify(inventoryService).deductFromPrescription(anyList(), eq("raw_herbs"));
    }

    @Test
    void prescriptionAutosavePersistsClinicalNotesWithoutAcceptingLifecycleFields()
    {
        TcmConsultation existing = consultation("context", new JSONObject()); existing.setStatus("draft");
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(inventoryService.deductFromPrescription(anyList(), eq("raw_herbs"))).thenReturn(deductSuccess("stock", "黄芪", "70", null));
        service.syncPrescription(existing.getId(), Map.of("prescription", prescription("rx",
                items(item("黄芪", "10", "g", "stock", null, "70")), null, "editing"),
                "consultation", Map.of("chiefComplaint", "Saved with stock", "progressOfDisease", "Clinical notes",
                        "status", "paid", "deletedAt", "bad", "paymentRecords", List.of(Map.of("amount", 999)))), "qa");
        JSONObject saved = JSON.parseObject(existing.getPayload());
        assertEquals("Saved with stock", saved.getString("chiefComplaint"));
        assertEquals("Clinical notes", saved.getString("progressOfDisease"));
        assertEquals("draft", existing.getStatus());
        assertEquals(null, existing.getDeletedAt());
        assertTrue(saved.getJSONArray("paymentRecords") == null || saved.getJSONArray("paymentRecords").isEmpty());
        assertEquals(1, saved.getJSONArray("prescriptions").getJSONObject(0).getJSONArray("inventoryReservation").size());
    }

    @Test
    void deletedParentRejectsLateSaveAndCannotLoseItsDeletedMarker()
    {
        TcmConsultation existing = consultation("late", new JSONObject()); existing.setStatus("draft"); existing.setDeletedAt("2026-10-13 09:00:00");
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        assertThrows(ServiceException.class, () -> service.updateTcmConsultation(consultation("late", new JSONObject()), "qa"));
        verify(consultationMapper, never()).updateTcmConsultation(any());
        org.mockito.Mockito.verifyNoInteractions(inventoryService);
    }

    @Test
    void parentDeletionAndRestoreIncludeDispensedStockAndAreIdempotent()
    {
        TcmConsultation existing = consultation("dispensed-parent", payloadWithPrescription(prescription("rx",
                items(item("黄芪", "10", "g", "stock", null, "70")), reservations(reservation("stock", "黄芪", "70", null)), "dispensed")));
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(inventoryService.restoreFromPrescription(anyList(), eq("raw_herbs"))).thenReturn(successResult());
        when(inventoryService.deductFromPrescription(anyList(), eq("raw_herbs"))).thenReturn(deductSuccess("stock", "黄芪", "70", null));
        service.softDeleteTcmConsultation(existing.getId()); service.softDeleteTcmConsultation(existing.getId());
        assertTrue(JSON.parseObject(existing.getPayload()).getJSONArray("prescriptions").getJSONObject(0).getJSONArray("inventoryReservation").isEmpty());
        service.restoreTcmConsultation(existing.getId()); service.restoreTcmConsultation(existing.getId());
        verify(inventoryService).restoreFromPrescription(anyList(), eq("raw_herbs"));
        verify(inventoryService).deductFromPrescription(anyList(), eq("raw_herbs"));
    }

    @Test
    void restoreLegacyDeletedParentDoesNotDeductStockThatWasStillHeld()
    {
        TcmConsultation existing = consultation("legacy-held", payloadWithPrescription(prescription("rx",
                items(item("黄芪", "10", "g", "stock", null, "70")), reservations(reservation("stock", "黄芪", "70", null)), "dispensed")));
        existing.setDeletedAt("2026-09-01 09:00:00");
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        service.restoreTcmConsultation(existing.getId());
        org.mockito.Mockito.verifyNoInteractions(inventoryService);
        assertEquals(null, existing.getDeletedAt());
    }

    @Test
    void permanentlyDeletingLegacyParentRestoresRemainingReservation()
    {
        TcmConsultation existing = consultation("legacy-purge", payloadWithPrescription(prescription("rx",
                items(item("黄芪", "10", "g", "stock", null, "70")), reservations(reservation("stock", "黄芪", "70", null)), "dispensed")));
        existing.setDeletedAt("2026-09-01 09:00:00");
        when(consultationMapper.selectTcmConsultationById(existing.getId())).thenReturn(existing);
        when(consultationMapper.deleteTcmConsultationById(existing.getId())).thenReturn(1);
        when(inventoryService.restoreFromPrescription(anyList(), eq("raw_herbs"))).thenReturn(successResult());
        assertEquals(1, service.hardDeleteTcmConsultation(existing.getId()));
        verify(inventoryService).restoreFromPrescription(anyList(), eq("raw_herbs"));
    }

    private TcmAppointment appointment(String id, String startTime, String practitionerId)
    {
        TcmAppointment appointment = new TcmAppointment();
        appointment.setId(id);
        appointment.setPatientId("patient-1");
        appointment.setPractitionerId(practitionerId);
        appointment.setStartTime(startTime);
        appointment.setStatus("booked");
        appointment.setServiceType("consultation");
        return appointment;
    }

    private TcmConsultation consultation(String id, JSONObject payload)
    {
        TcmConsultation consultation = new TcmConsultation();
        consultation.setId(id);
        consultation.setConsultationId("CONS-" + id);
        consultation.setPatientId("patient-1");
        consultation.setPractitionerId("doctor-1");
        consultation.setConsultDate("2026-04-06 10:00:00");
        consultation.setStatus("completed");
        consultation.setVersion(1);
        consultation.setPayload(payload.toJSONString());
        return consultation;
    }

    private JSONObject payloadWithPrescription(Map<String, Object> prescription)
    {
        JSONObject payload = new JSONObject();
        payload.put("prescriptions", Collections.singletonList(prescription));
        payload.put("totalAmount", 0);
        payload.put("taxAmount", 0);
        payload.put("totalWithoutTax", 0);
        return payload;
    }

    private Map<String, Object> prescription(
            String id,
            List<Map<String, Object>> items,
            List<Map<String, Object>> reservations,
            String rxStatus)
    {
        return prescription(id, "raw_herbs", items, reservations, rxStatus);
    }

    private Map<String, Object> prescription(
            String id,
            String prescriptionType,
            List<Map<String, Object>> items,
            List<Map<String, Object>> reservations,
            String rxStatus)
    {
        Map<String, Object> prescription = new LinkedHashMap<>();
        prescription.put("id", id);
        prescription.put("formulaName", "测试方");
        prescription.put("prescriptionType", prescriptionType);
        prescription.put("quantity", 7);
        prescription.put("items", items);
        if (reservations != null)
        {
            prescription.put("inventoryReservation", reservations);
        }
        prescription.put("rxStatus", rxStatus);
        return prescription;
    }

    private List<Map<String, Object>> items(Map<String, Object> item)
    {
        return new ArrayList<>(Collections.singletonList(item));
    }

    private Map<String, Object> item(
            String name,
            String dosage,
            String unit,
            String inventoryId,
            String supplierId,
            String convertedQty)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("dosage", dosage);
        item.put("unit", unit);
        item.put("inventoryId", inventoryId);
        item.put("supplierId", supplierId);
        item.put("convertedQty", convertedQty);
        return item;
    }

    private List<Map<String, Object>> reservations(Map<String, Object> reservation)
    {
        return new ArrayList<>(Collections.singletonList(reservation));
    }

    private Map<String, Object> herbal(
            String name,
            String dosage,
            String unit,
            String inventoryId,
            String convertedQty)
    {
        Map<String, Object> herbal = new LinkedHashMap<>();
        herbal.put("name", name);
        herbal.put("dosage", dosage);
        herbal.put("unit", unit);
        herbal.put("inventoryId", inventoryId);
        herbal.put("convertedQty", convertedQty);
        herbal.put("convertedUnit", unit);
        return herbal;
    }

    private Map<String, Object> reservation(String inventoryId, String name, String qty, String supplierId)
    {
        Map<String, Object> reservation = new LinkedHashMap<>();
        reservation.put("inventoryId", inventoryId);
        reservation.put("name", name);
        reservation.put("reservedQty", qty);
        reservation.put("supplierId", supplierId);
        return reservation;
    }

    private Map<String, Object> successResult()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("restored", Collections.emptyList());
        return result;
    }

    private Map<String, Object> deductSuccess(String inventoryId, String name, String qty, String supplierId)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        Map<String, Object> deducted = new LinkedHashMap<>();
        deducted.put("inventoryId", inventoryId);
        deducted.put("name", name);
        deducted.put("quantity", qty);
        deducted.put("supplierId", supplierId);
        result.put("deducted", Collections.singletonList(deducted));
        return result;
    }

    private Map<String, Object> deductFailure(String error)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("errors", Collections.singletonList(error));
        result.put("deducted", Collections.emptyList());
        return result;
    }
}
