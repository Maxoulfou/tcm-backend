package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.controller.TcmEmailLogController;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.domain.TcmEmailLog;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.mapper.TcmPatientMapper;
import com.ruoyi.hospital.service.ITcmEmailLogService;
import com.ruoyi.hospital.service.ITcmEmailService;
import com.ruoyi.hospital.service.ITcmPatientService;
import com.ruoyi.hospital.service.ITcmPdfService;

class TcmStripeInvoiceFailureTest
{
    private TcmStripePaymentServiceImpl service;
    private ITcmPdfService pdf;
    private ITcmEmailService email;
    private ITcmEmailLogService logs;
    private TcmConsultation consultation;
    private TcmPatient patient;
    private final Map<String, Object> payment = Map.of("amount", 125, "currency", "CAD");

    @BeforeEach void setup()
    {
        service = new TcmStripePaymentServiceImpl();
        pdf = mock(ITcmPdfService.class);
        email = mock(ITcmEmailService.class);
        logs = mock(ITcmEmailLogService.class);
        TcmPatientMapper patients = mock(TcmPatientMapper.class);
        consultation = new TcmConsultation();
        consultation.setId("invoice-consultation-uuid");
        consultation.setConsultationId("C-2026-001");
        consultation.setPatientId("invoice-patient");
        consultation.setConsultDate("2026-10-13");
        consultation.setPayload("{\"paymentRecords\":[{\"amount\":125}],\"paid\":true}");
        patient = new TcmPatient();
        patient.setId("invoice-patient");
        patient.setName("Acceptance Patient");
        patient.setEmail("acceptance@example.invalid");
        when(patients.selectTcmPatientById(patient.getId())).thenReturn(patient);
        when(logs.insertTcmEmailLog(any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "patientMapper", patients);
        ReflectionTestUtils.setField(service, "emailService", email);
        ReflectionTestUtils.setField(service, "emailLogService", logs);
        ReflectionTestUtils.setField(service, "pdfService", pdf);
    }

    private TcmEmailLog failGeneration()
    {
        when(pdf.generateInvoice(consultation.getId())).thenThrow(new ServiceException("Acceptance PDF storage unavailable"));
        ReflectionTestUtils.invokeMethod(service, "sendInvoiceEmail", consultation, payment);
        verifyNoInteractions(email);
        ArgumentCaptor<TcmEmailLog> captured = ArgumentCaptor.forClass(TcmEmailLog.class);
        verify(logs).insertTcmEmailLog(captured.capture());
        return captured.getValue();
    }

    private TcmEmailLogController resendController(TcmEmailLog failure)
    {
        failure.setId(50L);
        when(logs.selectTcmEmailLogById(50L)).thenReturn(failure);
        TcmEmailLogController controller = new TcmEmailLogController();
        ITcmPatientService patients = mock(ITcmPatientService.class);
        when(patients.selectTcmPatientById(patient.getId())).thenReturn(patient);
        ReflectionTestUtils.setField(controller, "emailLogService", logs);
        ReflectionTestUtils.setField(controller, "emailService", email);
        ReflectionTestUtils.setField(controller, "patientService", patients);
        ReflectionTestUtils.setField(controller, "pdfService", pdf);
        return controller;
    }

    @Test void pdfFailurePersistsRecoverableFailedLogWithoutSendingAnEmptyInvoice()
    {
        TcmEmailLog failure = failGeneration();
        JSONObject payload = JSONObject.parseObject(failure.getPayload());
        assertNull(failure.getSentAt());
        assertFalse(payload.getBooleanValue("sent"));
        assertTrue(payload.getBooleanValue("invoiceGenerationFailed"));
        assertEquals(consultation.getId(), payload.getString("invoiceConsultationId"));
        assertEquals("C-2026-001", payload.getJSONObject("variables").getString("consultationId"));
        assertEquals("invoice", failure.getEmailType());
        assertTrue(payload.getString("deliveryError").contains("Resend"));
    }

    @Test void resendRegeneratesFailedPdfAndSendsTheNewAttachment() throws Exception
    {
        TcmEmailLog failure = failGeneration();
        doReturn(Map.of("filePath", "hospital-private/acceptance/new-invoice.pdf"))
                .when(pdf).generateInvoice(consultation.getId());
        when(email.sendTemplateAndLog(anyString(), anyString(), anyMap(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(true);
        assertEquals(true, resendController(failure).resend(50L, null).get("success"));
        verify(pdf, times(2)).generateInvoice(consultation.getId());
        verify(email).sendTemplateAndLog(eq(patient.getEmail()), eq("invoice"), anyMap(), anyString(), anyString(), eq("invoice"),
                argThat(attachments -> attachments.size() == 1
                        && "hospital-private/acceptance/new-invoice.pdf".equals(attachments.get(0).get("resource"))));
    }

    @Test void resendStillFailingGenerationDoesNotSendOrPretendItSucceeded()
    {
        TcmEmailLog failure = failGeneration();
        assertThrows(ServiceException.class, () -> resendController(failure).resend(50L, null));
        assertNull(failure.getSentAt());
        verifyNoInteractions(email);
    }

    @Test void emptyPdfResultDoesNotReuseAnOldInvoiceOrSendWithoutAttachment()
    {
        consultation.setPayload("{\"invoicePdfPath\":\"hospital-private/old-unpaid-invoice.pdf\"}");
        when(pdf.generateInvoice(consultation.getId())).thenReturn(Collections.emptyMap());
        ReflectionTestUtils.invokeMethod(service, "sendInvoiceEmail", consultation, payment);
        verifyNoInteractions(email);
        verify(logs).insertTcmEmailLog(any());
    }

    @Test void rejectedExecutorRecordsAnInvoiceThatCanBeRetriedWithoutBlockingOnPdf()
    {
        ReflectionTestUtils.setField(service, "stripeWebhookTaskExecutor", (TaskExecutor) task -> {
            throw new RejectedExecutionException("Acceptance queue full");
        });
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "dispatchInvoiceEmail", consultation, payment));
        verify(logs).insertTcmEmailLog(any());
        verifyNoInteractions(pdf, email);
    }
}
