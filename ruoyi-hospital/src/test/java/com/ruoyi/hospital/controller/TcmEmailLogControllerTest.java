package com.ruoyi.hospital.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.hospital.domain.TcmEmailLog;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.service.ITcmEmailLogService;
import com.ruoyi.hospital.service.ITcmEmailService;
import com.ruoyi.hospital.service.ITcmPatientService;
import com.ruoyi.common.exception.ServiceException;

@ExtendWith(MockitoExtension.class)
class TcmEmailLogControllerTest
{
    @Mock
    private ITcmEmailLogService emailLogService;

    @Mock
    private ITcmEmailService emailService;

    @Mock
    private ITcmPatientService patientService;

    @Test
    void resend_shouldRejectHistoricalAttachmentFailureWhoseReferenceWasLost()
    {
        TcmEmailLog source = new TcmEmailLog();
        source.setId(41L);
        source.setToEmail("qa@example.invalid");
        source.setEmailType("invoice");
        source.setPayload("{\"attachmentError\":true,\"attachmentCount\":0}");
        when(emailLogService.selectTcmEmailLogById(41L)).thenReturn(source);

        assertThrows(ServiceException.class, () -> buildController().resend(41L, null));
        org.mockito.Mockito.verifyNoInteractions(emailService);
    }

    @Test
    void resend_shouldUseLatestPatientEmailForPatientFacingTemplate()
    {
        TcmEmailLog source = new TcmEmailLog();
        source.setId(9L);
        source.setToEmail("old@example.com");
        source.setSubject("Consent");
        source.setBody("Body");
        source.setEmailType("consent");
        source.setPayload("{\"templateKey\":\"consent\",\"variables\":{\"patientId\":\"patient-1\"}}");

        TcmPatient patient = new TcmPatient();
        patient.setId("patient-1");
        patient.setEmail("new@example.com");

        when(emailLogService.selectTcmEmailLogById(9L)).thenReturn(source);
        when(patientService.selectTcmPatientById("patient-1")).thenReturn(patient);
        when(emailService.sendTemplateAndLog(anyString(), anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(true);

        Map<String, Object> result = buildController().resend(9L, null);

        assertEquals("new@example.com", result.get("to"));
        verify(emailService).sendTemplateAndLog(
                eq("new@example.com"),
                eq("consent"),
                any(),
                eq("Consent"),
                eq("Body"),
                eq("consent"),
                any());
    }

    @Test
    void resend_shouldFindPatientByOldLoggedEmailWhenPatientIdIsMissing()
    {
        TcmEmailLog source = new TcmEmailLog();
        source.setId(10L);
        source.setToEmail("old@example.com");
        source.setSubject("Intake");
        source.setBody("Body");
        source.setEmailType("intake");
        source.setPayload("{\"templateKey\":\"intake\",\"variables\":{}}");

        TcmPatient patient = new TcmPatient();
        patient.setId("patient-1");
        patient.setEmail("new@example.com");
        patient.setPayload("{\"emails\":[\"old@example.com\"]}");

        when(emailLogService.selectTcmEmailLogById(10L)).thenReturn(source);
        when(patientService.selectTcmPatientList(any(TcmPatient.class))).thenReturn(Collections.singletonList(patient));
        when(emailService.sendTemplateAndLog(anyString(), anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(true);

        Map<String, Object> result = buildController().resend(10L, null);

        assertEquals("new@example.com", result.get("to"));
        verify(emailService).sendTemplateAndLog(
                eq("new@example.com"),
                eq("intake"),
                any(),
                eq("Intake"),
                eq("Body"),
                eq("intake"),
                any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_shouldAttachInvoicePdfFromLegacyAccessUrl()
    {
        Map<String, Object> body = new HashMap<>();
        body.put("to", "patient@example.com");
        body.put("templateKey", "invoice");
        body.put("type", "invoice");
        body.put("invoicePdfUrl",
                "https://www.otcm.app/api/public/files/access?resource=hospital-private%2F2026%2F05%2Finvoice.pdf");
        body.put("variables", Collections.singletonMap("patientId", "patient-1"));

        when(emailService.sendTemplateAndLog(anyString(), anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(true);

        buildController().create(body);

        ArgumentCaptor<List<Map<String, Object>>> attachmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendTemplateAndLog(
                eq("patient@example.com"),
                eq("invoice"),
                any(),
                any(),
                any(),
                eq("invoice"),
                attachmentsCaptor.capture());

        List<Map<String, Object>> attachments = attachmentsCaptor.getValue();
        assertEquals(1, attachments.size());
        assertEquals("hospital-private/2026/05/invoice.pdf", attachments.get(0).get("resource"));
    }

    @Test
    void create_shouldUseRegisteredTemplateWhenTypeAliasIsProvided()
    {
        Map<String, Object> body = new HashMap<>();
        body.put("to", "patient@example.com");
        body.put("type", "appointment_confirm");
        body.put("subject", "Raw subject");
        body.put("body", "Raw body");
        body.put("variables", Collections.singletonMap("patientName", "Patient One"));

        when(emailService.sendTemplateAndLog(anyString(), anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(true);

        Map<String, Object> result = buildController().create(body);

        assertEquals("appointmentConfirmation", result.get("templateKey"));
        verify(emailService).sendTemplateAndLog(
                eq("patient@example.com"),
                eq("appointmentConfirmation"),
                any(),
                eq("Raw subject"),
                eq("Raw body"),
                eq("appointment_confirm"),
                any());
        verify(emailService, never()).sendAndLog(anyString(), anyString(), anyString(), anyString(), any());
    }

    private TcmEmailLogController buildController()
    {
        TcmEmailLogController controller = new TcmEmailLogController();
        ReflectionTestUtils.setField(controller, "emailLogService", emailLogService);
        ReflectionTestUtils.setField(controller, "emailService", emailService);
        ReflectionTestUtils.setField(controller, "patientService", patientService);
        return controller;
    }
}
