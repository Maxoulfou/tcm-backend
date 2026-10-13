package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.hospital.controller.TcmEmailLogController;
import com.ruoyi.hospital.domain.TcmEmailLog;
import com.ruoyi.hospital.mapper.TcmClinicSettingMapper;
import com.ruoyi.hospital.mapper.TcmEmailLogMapper;
import com.ruoyi.hospital.service.ITcmEmailLogService;
import com.ruoyi.hospital.service.ITcmPatientService;
import com.ruoyi.hospital.util.HospitalFileStorage;

class TcmEmailAttachmentDeliveryTest
{
    @TempDir Path directory;
    private TcmEmailServiceImpl service;
    private JavaMailSender sender;
    private TcmEmailLogMapper logs;
    private HospitalFileStorage storage;

    @BeforeEach void setup()
    {
        service = new TcmEmailServiceImpl();
        sender = mock(JavaMailSender.class);
        logs = mock(TcmEmailLogMapper.class);
        storage = mock(HospitalFileStorage.class);
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        when(storage.resolve(anyString())).thenAnswer(call -> directory.resolve(Path.of((String) call.getArgument(0)).getFileName()));
        ReflectionTestUtils.setField(service, "mailSender", sender);
        ReflectionTestUtils.setField(service, "fromAddress", "qa@example.invalid");
        ReflectionTestUtils.setField(service, "emailLogMapper", logs);
        ReflectionTestUtils.setField(service, "clinicSettingMapper", mock(TcmClinicSettingMapper.class));
        ReflectionTestUtils.setField(service, "hospitalFileStorage", storage);
    }

    private Map<String, Object> attachment(String name)
    {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("resource", "hospital-private/acceptance/" + name);
        descriptor.put("fileName", name);
        return descriptor;
    }

    @Test void missingAttachmentSurvivesLogAndResendUntilFileIsRestored() throws Exception
    {
        assertFalse(service.sendAndLog("qa@example.invalid", "Invoice", "Body", "acceptance",
                Collections.singletonList(attachment("invoice.pdf"))));
        ArgumentCaptor<TcmEmailLog> captured = ArgumentCaptor.forClass(TcmEmailLog.class);
        verify(logs).insertTcmEmailLog(captured.capture());
        TcmEmailLog original = captured.getValue();
        original.setId(1L);
        JSONObject payload = JSONObject.parseObject(original.getPayload());
        assertNotNull(payload.getJSONArray("attachments"));
        assertEquals("hospital-private/acceptance/invoice.pdf",
                payload.getJSONArray("attachments").getJSONObject(0).getString("resource"));

        TcmEmailLogController controller = new TcmEmailLogController();
        ITcmEmailLogService logService = mock(ITcmEmailLogService.class);
        when(logService.selectTcmEmailLogById(1L)).thenReturn(original);
        ReflectionTestUtils.setField(controller, "emailLogService", logService);
        ReflectionTestUtils.setField(controller, "emailService", service);
        ReflectionTestUtils.setField(controller, "patientService", mock(ITcmPatientService.class));
        assertEquals(false, controller.resend(1L, null).get("success"));
        verify(sender, never()).send(any(MimeMessage.class));

        Files.write(directory.resolve("invoice.pdf"), "%PDF-1.4\nacceptance".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(true, controller.resend(1L, null).get("success"));
        verify(sender).send(any(MimeMessage.class));
    }

    @Test void partiallyResolvedAttachmentsCannotBeSentAsSuccessfulMail() throws Exception
    {
        Files.write(directory.resolve("first.pdf"), new byte[] {1, 2, 3});
        assertFalse(service.sendAndLog("qa@example.invalid", "Two attachments", "Body", "acceptance",
                Arrays.asList(attachment("first.pdf"), attachment("second.pdf"))));
        verify(sender, never()).send(any(MimeMessage.class));
        ArgumentCaptor<TcmEmailLog> captured = ArgumentCaptor.forClass(TcmEmailLog.class);
        verify(logs).insertTcmEmailLog(captured.capture());
        JSONObject payload = JSONObject.parseObject(captured.getValue().getPayload());
        assertEquals(2, payload.getJSONArray("attachments").size());
        assertTrue(payload.getBooleanValue("attachmentError"));
        assertNull(captured.getValue().getSentAt());
    }
}
