package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSONObject;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.hospital.domain.TcmClinicSetting;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.domain.TcmPatientFile;
import com.ruoyi.hospital.mapper.TcmClinicSettingMapper;
import com.ruoyi.hospital.mapper.TcmConsultationMapper;
import com.ruoyi.hospital.mapper.TcmPatientMapper;
import com.ruoyi.hospital.service.ITcmPatientFileService;
import com.ruoyi.hospital.util.ConsentDocumentTemplate;
import com.ruoyi.hospital.util.HospitalFileStorage;
import com.ruoyi.hospital.util.SignedFileUrlService;
import com.ruoyi.system.service.ISysUserService;

@ExtendWith(MockitoExtension.class)
class TcmPdfServiceImplTest
{
    @Mock
    private TcmConsultationMapper consultationMapper;

    @Mock
    private TcmPatientMapper patientMapper;

    @Mock
    private TcmClinicSettingMapper settingMapper;

    @Mock
    private SignedFileUrlService signedFileUrlService;

    @Mock
    private HospitalFileStorage hospitalFileStorage;

    @Mock
    private ISysUserService userService;

    @Mock
    private ITcmPatientFileService patientFileService;

    private TcmPdfServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp()
    {
        service = new TcmPdfServiceImpl();
        ReflectionTestUtils.setField(service, "consultationMapper", consultationMapper);
        ReflectionTestUtils.setField(service, "patientMapper", patientMapper);
        ReflectionTestUtils.setField(service, "settingMapper", settingMapper);
        ReflectionTestUtils.setField(service, "signedFileUrlService", signedFileUrlService);
        ReflectionTestUtils.setField(service, "hospitalFileStorage", hospitalFileStorage);
        ReflectionTestUtils.setField(service, "userService", userService);
        ReflectionTestUtils.setField(service, "patientFileService", patientFileService);
        org.mockito.Mockito.lenient().when(consultationMapper.updatePdfPayloadIfUnchanged(any(), any())).thenReturn(1);
    }

    @Test
    void generateConsultationReport_shouldRenderStructuredReportAndPersistFile() throws Exception
    {
        TcmConsultation consultation = new TcmConsultation();
        consultation.setId("consult-1");
        consultation.setConsultationId("CONS-001");
        consultation.setPatientId("patient-1");
        consultation.setConsultDate("2026-04-20 09:30:00");
        consultation.setStatus("completed");

        JSONObject diff = new JSONObject(new LinkedHashMap<>());
        diff.put("coldHeat", java.util.Arrays.asList("Aversion to cold 恶寒"));
        diff.put("sleep", java.util.Arrays.asList("Difficulty falling asleep 入睡困难"));
        diff.put("tongueImageResource", "hospital-private/test/tongue.png");
        List<Map<String, Object>> conclusions = new ArrayList<>();
        Map<String, Object> conclusion = new LinkedHashMap<>();
        conclusion.put("name", "Liver Qi Stagnation / 肝郁气滞");
        conclusion.put("treatment", "Soothe liver and regulate qi / 疏肝理气");
        conclusions.add(conclusion);
        diff.put("conclusions", conclusions);

        Map<String, Object> acu = new LinkedHashMap<>();
        acu.put("point", "LV3 太冲");
        acu.put("side", "bilateral");
        acu.put("notes", "20 minutes");

        Map<String, Object> herb = new LinkedHashMap<>();
        herb.put("name", "Chai Hu 柴胡");
        herb.put("dosage", 10);
        herb.put("unit", "g");

        Map<String, Object> rx = new LinkedHashMap<>();
        rx.put("formulaName", "Xiao Yao San / 逍遥散");
        rx.put("prescriptionType", "raw_herbs");
        rx.put("direction", "Oral");
        rx.put("quantity", 7);
        rx.put("items", java.util.Collections.singletonList(herb));
        rx.put("rxStatus", "pending");

        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("chiefComplaint", "Headache / 头痛");
        payload.put("chiefComplaintDuration", "2 weeks");
        payload.put("chiefComplaintDescription", "Worse at night");
        payload.put("progressOfDisease", "Gradually worse");
        payload.put("historyAndMedicationSnapshot", "No major surgery");
        payload.put("differentiation", "Liver Qi Stagnation");
        payload.put("diff", diff);
        payload.put("acupuncture", java.util.Collections.singletonList(acu));
        payload.put("treatment", "Acupuncture with herbal formula");
        payload.put("prognosis", "Follow up in one week");
        payload.put("prescriptions", java.util.Collections.singletonList(rx));
        consultation.setPayload(payload.toJSONString());

        TcmPatient patient = new TcmPatient();
        patient.setId("patient-1");
        patient.setName("Alice Zhang");
        patient.setFirstName("Alice");
        patient.setLastName("ZHANG");
        patient.setEmail("alice@example.com");
        patient.setPhone("416-555-0100");
        JSONObject patientPayload = new JSONObject(new LinkedHashMap<>());
        patientPayload.put("gender", "female");
        patientPayload.put("dateOfBirth", "1985-06-15");
        patientPayload.put("addressStreet", "100 Queen St W");
        patientPayload.put("addressCity", "Toronto");
        patientPayload.put("addressState", "ON");
        patientPayload.put("addressPostal", "M5H 2N2");
        JSONObject intake = new JSONObject(new LinkedHashMap<>());
        intake.put("currentMedications", "Ibuprofen");
        intake.put("medicalHistorySelections", java.util.Arrays.asList("Asthma / 哮喘"));
        intake.put("additionalNotes", "Works night shifts");
        patientPayload.put("latestIntakeFormData", intake);
        patientPayload.put("latestIntakeSubmittedAt", "2026-04-19 20:00:00");
        patient.setPayload(patientPayload.toJSONString());

        Path output = tempDir.resolve("report-test.pdf");
        Path tongueImage = tempDir.resolve("tongue.png");
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(image, "png", tongueImage.toFile());
        when(consultationMapper.selectTcmConsultationById("consult-1")).thenReturn(consultation);
        when(patientMapper.selectTcmPatientById("patient-1")).thenReturn(patient);
        when(hospitalFileStorage.createPatientFileResourceKey(
                "Alice Zhang", "consultation_report_pdf", "2026-04-20 09:30:00", ".pdf"))
                .thenReturn("hospital-private/test/report-test.pdf");
        when(hospitalFileStorage.resolve("hospital-private/test/report-test.pdf")).thenReturn(output);
        when(hospitalFileStorage.resolve("hospital-private/test/tongue.png")).thenReturn(tongueImage);
        when(signedFileUrlService.buildAccessUrl("hospital-private/test/report-test.pdf"))
                .thenReturn("/api/public/files/access?resource=hospital-private/test/report-test.pdf");
        when(settingMapper.selectSettingByKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return setting(key, "clinicName".equals(key) ? "仁和中医" : "");
        });

        List<TcmConsultation> updatedConsultations = new ArrayList<>();
        doAnswer(invocation -> {
            updatedConsultations.add(copyConsultation(invocation.getArgument(0)));
            return 1;
        }).when(consultationMapper).updatePdfPayloadIfUnchanged(any(TcmConsultation.class), anyString());

        List<TcmPatientFile> insertedFiles = new ArrayList<>();
        doAnswer(invocation -> {
            insertedFiles.add((TcmPatientFile) invocation.getArgument(0));
            return 1;
        }).when(patientFileService).insertTcmPatientFile(any(TcmPatientFile.class));

        Map<String, String> result = service.generateConsultationReport("consult-1");

        assertEquals("hospital-private/test/report-test.pdf", result.get("filePath"));
        assertTrue(Files.exists(output));
        String pdfText = readPdfText(output);
        assertTrue(pdfText.contains("Alice ZHANG"));
        assertTrue(pdfText.contains("Gender"));
        assertTrue(pdfText.contains("Female"));
        assertTrue(pdfText.contains("1985-06-15"));
        assertTrue(pdfText.contains("Date of Consultation"));
        assertTrue(pdfText.contains("Headache"));
        assertTrue(pdfText.contains("Chief Complaint Duration"));
        assertTrue(pdfText.contains("Chief Complaint Description"));
        assertTrue(pdfText.contains("Tongue Photo"));
        assertTrue(pdfText.contains("Liver Qi Stagnation"));
        assertTrue(pdfText.contains("LV3"));
        assertTrue(pdfText.contains("bilateral"));
        assertTrue(pdfText.contains("20 minutes"));
        assertTrue(pdfText.contains("Xiao Yao San"));
        assertTrue(pdfText.contains("Chai Hu"));
        assertTrue(pdfText.contains("Herbal Prescription"));
        assertTrue(pdfText.contains("Per Dose"));
        assertTrue(pdfText.contains("Direction"));
        assertTrue(pdfText.contains("Oral"));
        assertTrue(pdfText.contains("Total Doses"));
        assertTrue(pdfText.contains("7"));
        assertFalse(pdfText.contains("[\""));
        assertFalse(pdfText.contains("{}"));
        assertFalse(pdfText.contains("Patient Phone"));
        assertFalse(pdfText.contains("Patient Email"));
        assertFalse(pdfText.contains("Patient Address"));
        assertFalse(pdfText.contains("Initial Intake"));
        assertFalse(pdfText.contains("Ibuprofen"));
        assertFalse(pdfText.contains("Generated by TCM clinic system"));

        assertEquals(1, updatedConsultations.size());
        JSONObject updatedPayload = JSONObject.parseObject(updatedConsultations.get(0).getPayload());
        assertEquals("hospital-private/test/report-test.pdf", updatedPayload.getString("reportPdfPath"));
        assertEquals("hospital-private/test/report-test.pdf", updatedPayload.getString("consultationPdfPath"));

        assertEquals(1, insertedFiles.size());
        assertEquals("consultation_report_pdf", insertedFiles.get(0).getFileType());
        assertEquals("consult-1", insertedFiles.get(0).getConsultationId());
        verify(hospitalFileStorage).resolve("hospital-private/test/tongue.png");
    }

    @Test
    void generateConsultationReport_shouldRegenerateWhenExistingReportPath() throws Exception
    {
        TcmConsultation consultation = new TcmConsultation();
        consultation.setId("consult-existing");
        consultation.setConsultationId("CONS-EXISTING");
        consultation.setPatientId("patient-1");
        consultation.setConsultDate("2026-05-17");
        consultation.setStatus("draft");
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("reportPdfPath", "hospital-private/test/existing-report.pdf");
        payload.put("chiefComplaint", "Back Pain");
        consultation.setPayload(payload.toJSONString());

        TcmPatient patient = new TcmPatient();
        patient.setId("patient-1");
        patient.setName("Yuanyuan Fang");

        Path output = tempDir.resolve("report-regenerated.pdf");
        when(consultationMapper.selectTcmConsultationById("consult-existing")).thenReturn(consultation);
        when(patientMapper.selectTcmPatientById("patient-1")).thenReturn(patient);
        when(hospitalFileStorage.createPatientFileResourceKey(
                "Yuanyuan Fang", "consultation_report_pdf", "2026-05-17", ".pdf"))
                .thenReturn("hospital-private/test/report-regenerated.pdf");
        when(hospitalFileStorage.resolve("hospital-private/test/report-regenerated.pdf")).thenReturn(output);
        when(signedFileUrlService.buildAccessUrl("hospital-private/test/report-regenerated.pdf"))
                .thenReturn("/api/public/files/access?resource=hospital-private/test/report-regenerated.pdf");
        when(settingMapper.selectSettingByKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return setting(key, "clinicName".equals(key) ? "OTCM Acupuncture Clinic" : "");
        });

        List<TcmConsultation> updatedConsultations = new ArrayList<>();
        doAnswer(invocation -> {
            updatedConsultations.add(copyConsultation(invocation.getArgument(0)));
            return 1;
        }).when(consultationMapper).updatePdfPayloadIfUnchanged(any(TcmConsultation.class), anyString());

        Map<String, String> result = service.generateConsultationReport("consult-existing");

        assertEquals("hospital-private/test/report-regenerated.pdf", result.get("filePath"));
        assertTrue(Files.exists(output));
        String pdfText = readPdfText(output);
        assertTrue(pdfText.contains("Consultation Report"));
        assertTrue(pdfText.contains("Back Pain"));
        assertFalse(pdfText.contains("Generated by TCM clinic system"));

        assertEquals(1, updatedConsultations.size());
        JSONObject updatedPayload = JSONObject.parseObject(updatedConsultations.get(0).getPayload());
        assertEquals("hospital-private/test/report-regenerated.pdf", updatedPayload.getString("reportPdfPath"));
        verify(patientFileService).insertTcmPatientFile(any(TcmPatientFile.class));
    }

    @Test
    void generateInvoice_shouldIncludePractitionerIdentityAndOrganization() throws Exception
    {
        TcmConsultation consultation = new TcmConsultation();
        consultation.setId("consult-invoice");
        consultation.setConsultationId("INV-001");
        consultation.setPatientId("patient-1");
        consultation.setPractitionerId("42");
        consultation.setConsultDate("2026-04-20 09:30:00");
        consultation.setStatus("completed");
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("currency", "CAD");
        payload.put("consultationFee", 80);
        payload.put("totalWithoutTax", 195);
        payload.put("taxAmount", 14.95);
        payload.put("totalAmount", 209.95);
        payload.put("includeRxAmount", true);
        payload.put("overrideTaxRate", 0.13);
        Map<String, Object> serviceItem = new LinkedHashMap<>();
        serviceItem.put("name", "Acupuncture");
        serviceItem.put("price", 100);
        serviceItem.put("quantity", 1);
        payload.put("services", java.util.Collections.singletonList(serviceItem));
        Map<String, Object> herb = new LinkedHashMap<>();
        herb.put("name", "Chai Hu");
        herb.put("dosage", 10);
        herb.put("unit", "g");
        Map<String, Object> rx = new LinkedHashMap<>();
        rx.put("formulaName", "Xiao Yao San");
        rx.put("prescriptionType", "raw_herbs");
        rx.put("quantity", 3);
        rx.put("perDoseSubtotal", 5);
        rx.put("subtotal", 15);
        rx.put("rxStatus", "dispensed");
        rx.put("items", java.util.Collections.singletonList(herb));
        payload.put("prescriptions", java.util.Collections.singletonList(rx));
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", "pay-1");
        payment.put("amount", 209.95);
        payment.put("method", "cash");
        payload.put("paymentRecords", java.util.Collections.singletonList(payment));
        consultation.setPayload(payload.toJSONString());

        TcmPatient patient = new TcmPatient();
        patient.setId("patient-1");
        patient.setName("Alice Zhang");

        JSONObject profile = new JSONObject(new LinkedHashMap<>());
        profile.put("title", "R.Ac");
        profile.put("regulatoryBody", "CTCMPAO");
        profile.put("registrationNumber", "6995");
        JSONObject practitionerSignature = new JSONObject(new LinkedHashMap<>());
        practitionerSignature.put("path", "hospital-private/test/practitioner-signature.png");
        profile.put("signature", practitionerSignature);
        SysUser practitioner = new SysUser();
        practitioner.setUserId(42L);
        practitioner.setNickName("Dr. Chen");
        practitioner.setEmail("dr.chen@example.com");
        practitioner.setPhonenumber("604-555-0100");
        practitioner.setRemark(profile.toJSONString());

        Path output = tempDir.resolve("invoice-test.pdf");
        when(consultationMapper.selectTcmConsultationById("consult-invoice")).thenReturn(consultation);
        when(patientMapper.selectTcmPatientById("patient-1")).thenReturn(patient);
        when(userService.selectUserById(42L)).thenReturn(practitioner);
        when(hospitalFileStorage.createPatientFileResourceKey(
                "Alice Zhang", "invoice_pdf", "2026-04-20 09:30:00", ".pdf"))
                .thenReturn("hospital-private/test/invoice-test.pdf");
        when(hospitalFileStorage.resolve("hospital-private/test/invoice-test.pdf")).thenReturn(output);
        when(signedFileUrlService.buildAccessUrl("hospital-private/test/invoice-test.pdf"))
                .thenReturn("/api/public/files/access?resource=hospital-private/test/invoice-test.pdf");
        when(settingMapper.selectSettingByKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("thirdPartySignature".equals(key))
            {
                return setting(key, "{\"path\":\"hospital-private/test/third-party.png\"}");
            }
            if ("clinicSeal".equals(key))
            {
                return setting(key, "{\"path\":\"hospital-private/test/clinic-seal.png\"}");
            }
            return setting(key, "clinicName".equals(key) ? "仁和中医" : "");
        });
        List<TcmConsultation> updatedConsultations = new ArrayList<>();
        doAnswer(invocation -> {
            updatedConsultations.add(copyConsultation(invocation.getArgument(0)));
            return 1;
        }).when(consultationMapper).updatePdfPayloadIfUnchanged(any(TcmConsultation.class), anyString());

        Map<String, String> result = service.generateInvoice("consult-invoice");

        assertEquals("hospital-private/test/invoice-test.pdf", result.get("filePath"));
        String pdfText = readPdfText(output);
        assertTrue(pdfText.contains("Dr. Chen"));
        assertTrue(pdfText.contains("CTCMPAO"));
        assertTrue(pdfText.contains("6995"));
        assertTrue(pdfText.contains("Practitioner Name: Dr. Chen"));
        assertTrue(pdfText.contains("organisation: CTCMPAO"));
        assertTrue(pdfText.contains("organisation code: 6995"));
        assertFalse(pdfText.contains("R.Ac"));
        assertFalse(pdfText.contains("dr.chen@example.com"));
        assertFalse(pdfText.contains("Practitioner Email"));
        assertFalse(pdfText.contains("Practitioner Phone"));
        assertFalse(pdfText.contains("Consultation ID"));
        assertFalse(pdfText.contains("Patient Phone"));
        assertFalse(pdfText.contains("Practitioner ID"));
        assertFalse(pdfText.contains("Record Type"));
        assertFalse(pdfText.contains("Service Items"));
        assertTrue(pdfText.contains("Consultation Fee"));
        assertTrue(pdfText.contains("Acupuncture"));
        assertTrue(pdfText.contains("Xiao Yao San"));
        assertTrue(pdfText.contains("HST (13%)"));
        assertTrue(pdfText.contains("CAD 195.00"));
        assertTrue(pdfText.contains("CAD 14.95"));
        assertTrue(pdfText.contains("CAD 209.95"));
        assertTrue(pdfText.contains("Paid"));
        assertTrue(pdfText.contains("Balance"));
        assertTrue(pdfText.contains("Signatures"));
        assertTrue(pdfText.contains("Practitioner Signature"));
        assertTrue(pdfText.contains("Clinic Seal"));
        assertTrue(pdfText.contains("Generated by OTCM Acupuncture Clinic -"));
        assertFalse(pdfText.contains("Paid Amount"));
        assertFalse(pdfText.contains("Balance Amount"));
        assertFalse(pdfText.contains("Chai Hu"));
        assertFalse(pdfText.contains("Generated by TCM clinic system"));
        assertEquals(1, updatedConsultations.size());
        JSONObject updatedPayload = JSONObject.parseObject(updatedConsultations.get(0).getPayload());
        assertEquals("hospital-private/test/invoice-test.pdf", updatedPayload.getString("invoicePdfPath"));
        assertEquals("/api/public/files/access?resource=hospital-private/test/invoice-test.pdf",
                updatedPayload.getString("invoicePdfUrl"));
        verify(hospitalFileStorage).resolve("hospital-private/test/practitioner-signature.png");
        verify(hospitalFileStorage).resolve("hospital-private/test/clinic-seal.png");
        verify(hospitalFileStorage, never()).resolve("hospital-private/test/third-party.png");
    }

    @Test
    void generateInvoice_shouldAttachThirdPartyImageWhenEnabled() throws Exception
    {
        TcmConsultation consultation = new TcmConsultation();
        consultation.setId("consult-third-party");
        consultation.setConsultationId("INV-3P");
        consultation.setPatientId("patient-1");
        consultation.setConsultDate("2026-04-20 09:30:00");
        consultation.setStatus("completed");
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("add3rdParty", true);
        payload.put("totalWithoutTax", 0);
        payload.put("taxAmount", 0);
        payload.put("totalAmount", 0);
        consultation.setPayload(payload.toJSONString());

        TcmPatient patient = new TcmPatient();
        patient.setId("patient-1");
        patient.setName("Alice Zhang");

        Path output = tempDir.resolve("invoice-third-party.pdf");
        when(consultationMapper.selectTcmConsultationById("consult-third-party")).thenReturn(consultation);
        when(patientMapper.selectTcmPatientById("patient-1")).thenReturn(patient);
        when(hospitalFileStorage.createPatientFileResourceKey(
                "Alice Zhang", "invoice_pdf", "2026-04-20 09:30:00", ".pdf"))
                .thenReturn("hospital-private/test/invoice-third-party.pdf");
        when(hospitalFileStorage.resolve("hospital-private/test/invoice-third-party.pdf")).thenReturn(output);
        when(signedFileUrlService.buildAccessUrl("hospital-private/test/invoice-third-party.pdf"))
                .thenReturn("/api/public/files/access?resource=hospital-private/test/invoice-third-party.pdf");
        when(settingMapper.selectSettingByKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("thirdPartySignature".equals(key))
            {
                return setting(key, "{\"path\":\"hospital-private/test/third-party.png\"}");
            }
            return setting(key, "clinicName".equals(key) ? "OTCM Acupuncture Clinic" : "");
        });

        service.generateInvoice("consult-third-party");

        verify(hospitalFileStorage).resolve("hospital-private/test/third-party.png");
    }

    @Test
    void generateConsentForm_shouldCreatePdfArchiveAndPersistMeta() throws Exception
    {
        TcmPatient patient = new TcmPatient();
        patient.setId("patient-1");
        patient.setName("张三");
        patient.setConsentSignedAt("2026-04-19 12:00:00");

        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("consentDocumentTitle", "OTCM Informed Consent / OTCM 知情同意书");
        payload.put("consentVersion", ConsentDocumentTemplate.getVersion());
        payload.put("consentDocumentSections", ConsentDocumentTemplate.toResponseSections());
        JSONObject acknowledgements = new JSONObject(new LinkedHashMap<>());
        for (String key : ConsentDocumentTemplate.getSectionKeys())
        {
            acknowledgements.put(key, true);
        }
        payload.put("consentSectionAcknowledgements", acknowledgements);
        patient.setPayload(payload.toJSONString());

        Path output = tempDir.resolve("consent-test.pdf");
        when(patientMapper.selectTcmPatientById("patient-1")).thenReturn(patient);
        when(hospitalFileStorage.createPatientFileResourceKey(
                "张三", "consent_pdf", "2026-04-19 12:00:00", ".pdf"))
                .thenReturn("hospital-private/test/consent-test.pdf");
        when(hospitalFileStorage.resolve("hospital-private/test/consent-test.pdf")).thenReturn(output);
        when(signedFileUrlService.buildAccessUrl("hospital-private/test/consent-test.pdf"))
                .thenReturn("/api/public/files/access?resource=hospital-private/test/consent-test.pdf");
        when(settingMapper.selectSettingByKey("clinicName")).thenReturn(setting("clinicName", "仁和中医"));
        when(settingMapper.selectSettingByKey("clinicAddress")).thenReturn(setting("clinicAddress", "深圳市南山区科技园"));
        when(settingMapper.selectSettingByKey("clinicPhone")).thenReturn(setting("clinicPhone", "0755-12345678"));

        List<TcmPatient> updatedPatients = new ArrayList<>();
        doAnswer(invocation -> {
            updatedPatients.add(copyPatient(invocation.getArgument(0)));
            return 1;
        }).when(patientMapper).updateTcmPatient(any(TcmPatient.class));

        List<TcmPatientFile> insertedFiles = new ArrayList<>();
        doAnswer(invocation -> {
            insertedFiles.add((TcmPatientFile) invocation.getArgument(0));
            return 1;
        }).when(patientFileService).insertTcmPatientFile(any(TcmPatientFile.class));

        Map<String, String> result = service.generateConsentForm("patient-1", "Alice Zhang");

        assertEquals("hospital-private/test/consent-test.pdf", result.get("filePath"));
        assertEquals("/api/public/files/access?resource=hospital-private/test/consent-test.pdf", result.get("url"));
        assertTrue(Files.exists(output));
        assertTrue(Files.size(output) > 0);

        String pdfText = readPdfText(output);
        assertTrue(pdfText.contains("OTCM Informed Consent"));
        assertTrue(pdfText.contains(ConsentDocumentTemplate.getVersion()));
        assertTrue(pdfText.contains("Patient Consent"));
        assertTrue(pdfText.contains("Consent Statement"));
        assertTrue(pdfText.contains("Alice Zhang"));
        assertTrue(pdfText.contains("[v] I read and agree."));
        assertTrue(pdfText.contains("我已阅读并同意"));
        assertTrue(pdfText.contains("2026-04-19 12:00:00"));
        assertFalse(pdfText.contains("Generated by TCM clinic system"));

        assertEquals(1, updatedPatients.size());
        JSONObject updatedPayload = JSONObject.parseObject(updatedPatients.get(0).getPayload());
        assertEquals("hospital-private/test/consent-test.pdf", updatedPayload.getString("consentPdfPath"));
        assertEquals("/api/public/files/access?resource=hospital-private/test/consent-test.pdf",
                updatedPayload.getString("consentPdfUrl"));
        assertTrue(updatedPayload.containsKey("consentPdfGeneratedAt"));

        assertEquals(1, insertedFiles.size());
        assertEquals("patient-1", insertedFiles.get(0).getPatientId());
        assertEquals("consent_pdf", insertedFiles.get(0).getFileType());
        assertEquals("hospital-private/test/consent-test.pdf", insertedFiles.get(0).getFilePath());

        verify(patientMapper).selectTcmPatientById("patient-1");
        verify(patientMapper).updateTcmPatient(any(TcmPatient.class));
        verify(patientFileService).insertTcmPatientFile(any(TcmPatientFile.class));
        verify(hospitalFileStorage).createPatientFileResourceKey(
                "张三", "consent_pdf", "2026-04-19 12:00:00", ".pdf");
        verify(hospitalFileStorage).resolve("hospital-private/test/consent-test.pdf");
        verify(signedFileUrlService).buildAccessUrl("hospital-private/test/consent-test.pdf");
    }

    private String readPdfText(Path path) throws Exception
    {
        StringBuilder text = new StringBuilder();
        try (PdfReader reader = new PdfReader(path.toString()); PdfDocument document = new PdfDocument(reader))
        {
            for (int page = 1; page <= document.getNumberOfPages(); page++)
            {
                text.append(PdfTextExtractor.getTextFromPage(document.getPage(page))).append('\n');
            }
        }
        return text.toString();
    }

    private TcmClinicSetting setting(String key, String value)
    {
        TcmClinicSetting setting = new TcmClinicSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        return setting;
    }

    private TcmPatient copyPatient(TcmPatient source)
    {
        TcmPatient copy = new TcmPatient();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setPayload(source.getPayload());
        return copy;
    }

    private TcmConsultation copyConsultation(TcmConsultation source)
    {
        TcmConsultation copy = new TcmConsultation();
        copy.setId(source.getId());
        copy.setConsultationId(source.getConsultationId());
        copy.setPatientId(source.getPatientId());
        copy.setPayload(source.getPayload());
        return copy;
    }
}
