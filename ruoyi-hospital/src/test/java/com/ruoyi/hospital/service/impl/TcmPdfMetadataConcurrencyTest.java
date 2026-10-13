package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.mapper.TcmConsultationMapper;

class TcmPdfMetadataConcurrencyTest
{
    @Test
    void pdfGeneratedFromOldSnapshotPreservesNewerPaymentAndOnlyUpdatesPayload()
    {
        TcmPdfServiceImpl service = new TcmPdfServiceImpl();
        TcmConsultationMapper mapper = mock(TcmConsultationMapper.class);
        ReflectionTestUtils.setField(service, "consultationMapper", mapper);
        TcmConsultation original = consultation("{\"paymentRecords\":[]}");
        TcmConsultation current = consultation("{\"paymentRecords\":[{\"amount\":125}],\"notes\":\"latest\"}");
        when(mapper.selectTcmConsultationById("consult-pdf")).thenReturn(current);
        when(mapper.updatePdfPayloadIfUnchanged(any(), eq(current.getPayload()))).thenReturn(1);
        ReflectionTestUtils.invokeMethod(service, "updateConsultationPdfMeta", original,
                Map.of("filePath", "invoice.pdf", "url", "/invoice.pdf"), "invoice");
        ArgumentCaptor<TcmConsultation> captor = ArgumentCaptor.forClass(TcmConsultation.class);
        verify(mapper).updatePdfPayloadIfUnchanged(captor.capture(), eq(current.getPayload()));
        JSONObject saved = JSON.parseObject(captor.getValue().getPayload());
        assertEquals(125, saved.getJSONArray("paymentRecords").getJSONObject(0).getIntValue("amount"));
        assertEquals("latest", saved.getString("notes"));
        assertEquals("invoice.pdf", saved.getString("invoicePdfPath"));
        assertNull(captor.getValue().getStatus());
        verify(mapper, never()).updateTcmConsultation(any());
    }

    @Test
    void concurrentEditBetweenReadAndWriteIsMergedOnRetry()
    {
        TcmPdfServiceImpl service = new TcmPdfServiceImpl();
        TcmConsultationMapper mapper = mock(TcmConsultationMapper.class);
        ReflectionTestUtils.setField(service, "consultationMapper", mapper);
        TcmConsultation first = consultation("{\"notes\":\"first\"}");
        TcmConsultation latest = consultation("{\"notes\":\"second\",\"paid\":true}");
        when(mapper.selectTcmConsultationById("consult-pdf")).thenReturn(first, latest);
        when(mapper.updatePdfPayloadIfUnchanged(any(), any())).thenReturn(0, 1);
        ReflectionTestUtils.invokeMethod(service, "updateConsultationPdfMeta", first,
                Map.of("filePath", "report.pdf", "url", "/report.pdf"), "report");
        ArgumentCaptor<TcmConsultation> captor = ArgumentCaptor.forClass(TcmConsultation.class);
        verify(mapper, times(2)).updatePdfPayloadIfUnchanged(captor.capture(), any());
        JSONObject saved = JSON.parseObject(captor.getAllValues().get(1).getPayload());
        assertEquals("second", saved.getString("notes"));
        assertTrue(saved.getBooleanValue("paid"));
        assertEquals("/report.pdf", saved.getString("consultationPdfUrl"));
    }

    @Test
    void exhaustedCompareAndSetDoesNotReportSuccessfulMetadataSave()
    {
        TcmPdfServiceImpl service = new TcmPdfServiceImpl();
        TcmConsultationMapper mapper = mock(TcmConsultationMapper.class);
        ReflectionTestUtils.setField(service, "consultationMapper", mapper);
        TcmConsultation original = consultation("{}");
        when(mapper.selectTcmConsultationById("consult-pdf")).thenReturn(original);
        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(service, "updateConsultationPdfMeta",
                original, Map.of("filePath", "invoice.pdf", "url", "/invoice.pdf"), "invoice"));
        verify(mapper, times(3)).updatePdfPayloadIfUnchanged(any(), any());
    }

    private TcmConsultation consultation(String payload)
    {
        TcmConsultation result = new TcmConsultation();
        result.setId("consult-pdf");
        result.setPayload(payload);
        return result;
    }
}
