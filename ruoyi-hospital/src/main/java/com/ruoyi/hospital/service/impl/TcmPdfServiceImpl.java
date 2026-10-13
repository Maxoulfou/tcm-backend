package com.ruoyi.hospital.service.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.io.image.ImageDataFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.hospital.domain.TcmClinicSetting;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.domain.TcmHerbDict;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.domain.TcmPatientFile;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.hospital.mapper.TcmClinicSettingMapper;
import com.ruoyi.hospital.mapper.TcmConsultationMapper;
import com.ruoyi.hospital.mapper.TcmHerbDictMapper;
import com.ruoyi.hospital.mapper.TcmPatientMapper;
import com.ruoyi.hospital.service.ITcmPdfService;
import com.ruoyi.hospital.service.ITcmPatientFileService;
import com.ruoyi.hospital.util.ConsentDocumentTemplate;
import com.ruoyi.hospital.util.HospitalFileStorage;
import com.ruoyi.hospital.util.SignedFileUrlService;
import com.ruoyi.system.service.ISysUserService;

@Service
public class TcmPdfServiceImpl implements ITcmPdfService
{
    private static final Logger log = LoggerFactory.getLogger(TcmPdfServiceImpl.class);
    private static final DeviceRgb PRIMARY_COLOR = new DeviceRgb(45, 106, 79);
    private static final DeviceRgb SOFT_GREEN = new DeviceRgb(239, 246, 241);
    private static final DeviceRgb BORDER_GREEN = new DeviceRgb(220, 232, 224);
    private static final DeviceRgb MUTED_TEXT = new DeviceRgb(89, 98, 92);
    private static final float REPORT_IMAGE_MAX_WIDTH = 170f;
    private static final float REPORT_IMAGE_MAX_HEIGHT = 55f;
    private static final float INVOICE_IMAGE_MAX_WIDTH = 150f;
    private static final float INVOICE_IMAGE_MAX_HEIGHT = 55f;
    private static final String DEFAULT_CLINIC_NAME = "OTCM Acupuncture Clinic";

    @Autowired
    private TcmConsultationMapper consultationMapper;
    @Autowired
    private TcmPatientMapper patientMapper;
    @Autowired
    private TcmClinicSettingMapper settingMapper;
    @Autowired
    private TcmHerbDictMapper herbDictMapper;
    @Autowired
    private SignedFileUrlService signedFileUrlService;
    @Autowired
    private HospitalFileStorage hospitalFileStorage;
    @Autowired
    private ISysUserService userService;

    @Autowired
    private ITcmPatientFileService patientFileService;

    @Override
    public Map<String, String> generateConsultationReport(String consultationId)
    {
        TcmConsultation consultation = consultationMapper.selectTcmConsultationById(consultationId);
        if (consultation == null)
        {
            throw new ServiceException("consultation not found");
        }

        JSONObject payload = parsePayload(consultation.getPayload());
        TcmPatient patient = patientMapper.selectTcmPatientById(consultation.getPatientId());
        JSONObject patientPayload = parsePayload(patient != null ? patient.getPayload() : null);
        String clinicName = getClinicName();
        JSONObject practitionerProfile = resolvePractitionerProfile(consultation);
        String resourcePath = hospitalFileStorage.createPatientFileResourceKey(
                patient != null ? patient.getName() : consultation.getPatientId(),
                "consultation_report_pdf",
                consultation.getConsultDate(),
                ".pdf");
        String filePath = hospitalFileStorage.resolve(resourcePath).toString();
        ensureDir(filePath);

        try
        {
            PdfWriter writer = new PdfWriter(new FileOutputStream(filePath));
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf);
            PdfFont font = createFont();

            addHeader(doc, font, clinicName, "Consultation Report / 问诊报告");
            addReportConsultationInfo(doc, font, consultation, patient, patientPayload);
            addChiefComplaintSection(doc, font, payload);
            addDifferentiationSection(doc, font, payload);
            addTongueImage(doc, font, payload.getJSONObject("diff"));
            addTreatmentInfoSection(doc, font, payload);
            addAcupunctureSection(doc, font, payload.getJSONArray("acupuncture"));
            addFormulaCompositionSection(doc, font, payload);
            addPractitionerSignature(doc, font, practitionerProfile);
            addClinicSeal(doc);
            doc.close();
            hospitalFileStorage.backupResource(resourcePath);
            log.info("Consultation report PDF generated: {}", filePath);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("Failed to generate consultation report PDF", e);
            throw new ServiceException("PDF generation failed: " + e.getMessage());
        }

        Map<String, String> result = buildResult(resourcePath);
        updateConsultationPdfMeta(consultation, result, "report");
        insertConsultationFileRecord(consultation, "consultation_report_pdf", "consultation-report", resourcePath);
        return result;
    }

    @Override
    public Map<String, String> generateInvoice(String consultationId)
    {
        TcmConsultation consultation = consultationMapper.selectTcmConsultationById(consultationId);
        if (consultation == null)
        {
            throw new ServiceException("consultation not found");
        }

        TcmPatient patient = patientMapper.selectTcmPatientById(consultation.getPatientId());
        JSONObject payload = parsePayload(consultation.getPayload());
        String currency = resolveCurrency(payload);
        JSONObject patientPayload = parsePayload(patient != null ? patient.getPayload() : null);
        String clinicName = getClinicName();
        String clinicAddress = getClinicSetting("clinicAddress");
        String clinicPhone = getClinicSetting("clinicPhone");

        JSONObject practitionerProfile = resolvePractitionerProfile(consultation);

        String resourcePath = hospitalFileStorage.createPatientFileResourceKey(
                patient != null ? patient.getName() : consultation.getPatientId(),
                "invoice_pdf",
                consultation.getConsultDate(),
                ".pdf");
        String filePath = hospitalFileStorage.resolve(resourcePath).toString();
        ensureDir(filePath);

        try
        {
            PdfWriter writer = new PdfWriter(new FileOutputStream(filePath));
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf);
            PdfFont font = createFont();

            addHeader(doc, font, clinicName, "Invoice");
            addInvoiceClinicInfo(doc, font, clinicName, clinicAddress, clinicPhone, practitionerProfile);
            addInvoiceBillTo(doc, font, patient, patientPayload);
            addInvoiceConsultationDate(doc, font, consultation);
            addInvoiceItems(doc, font, payload, currency);
            addInvoiceTotals(doc, font, payload, currency);
            addInvoiceSignatureSection(doc, font, practitionerProfile, payload);
            addFooter(doc, font);
            doc.close();
            hospitalFileStorage.backupResource(resourcePath);
            log.info("Invoice PDF generated: {}", filePath);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("Failed to generate invoice PDF", e);
            throw new ServiceException("PDF generation failed: " + e.getMessage());
        }

        Map<String, String> result = buildResult(resourcePath);
        updateConsultationPdfMeta(consultation, result, "invoice");
        insertConsultationFileRecord(consultation, "invoice_pdf", "invoice", resourcePath);
        return result;
    }

    @Override
    public Map<String, String> generateConsentForm(String patientId, String signatureName)
    {
        TcmPatient patient = patientMapper.selectTcmPatientById(patientId);
        if (patient == null)
        {
            throw new ServiceException("patient not found");
        }

        String clinicName = getClinicName();
        String clinicAddress = getClinicSetting("clinicAddress");
        String clinicPhone = getClinicSetting("clinicPhone");
        String resourcePath = hospitalFileStorage.createPatientFileResourceKey(
                patient.getName(),
                "consent_pdf",
                patient.getConsentSignedAt(),
                ".pdf");
        String filePath = hospitalFileStorage.resolve(resourcePath).toString();
        ensureDir(filePath);

        JSONObject patientPayload = parsePayload(patient.getPayload());
        String signedAt = StringUtils.defaultIfBlank(
                StringUtils.defaultIfBlank(patient.getConsentSignedAt(), patientPayload.getString("consentSignedAt")),
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        Object configuredConsentTemplate = getClinicSetting("consentTemplate");
        String consentTitle = StringUtils.defaultIfBlank(
                patientPayload.getString("consentDocumentTitle"),
                ConsentDocumentTemplate.getTitle(configuredConsentTemplate));
        String consentVersion = StringUtils.defaultIfBlank(
                patientPayload.getString("consentVersion"),
                ConsentDocumentTemplate.getVersion(configuredConsentTemplate));
        String displaySignature = StringUtils.defaultIfBlank(
                StringUtils.defaultIfBlank(signatureName, patientPayload.getString("consentSignatureName")),
                patient.getName());
        List<Map<String, Object>> sections = extractConsentSections(
                patientPayload.get("consentDocumentSections"),
                configuredConsentTemplate);
        JSONObject consentAcknowledgements = toJsonObject(patientPayload.get("consentSectionAcknowledgements"));

        try
        {
            PdfWriter writer = new PdfWriter(new FileOutputStream(filePath));
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf);
            PdfFont font = createFont();

            addHeader(doc, font, clinicName, consentTitle);
            doc.add(new Paragraph("Patient / 患者：" + safeValue(patient.getName())).setFont(font).setFontSize(11));
            doc.add(new Paragraph("Version / 版本：" + safeValue(consentVersion)).setFont(font).setFontSize(10));
            if (StringUtils.isNotBlank(clinicAddress))
            {
                doc.add(new Paragraph("Clinic address / 诊所地址：" + clinicAddress).setFont(font).setFontSize(10));
            }
            if (StringUtils.isNotBlank(clinicPhone))
            {
                doc.add(new Paragraph("Phone / 联系电话：" + clinicPhone).setFont(font).setFontSize(10));
            }

            addSectionTitle(doc, font, "Consent Content / 同意书内容");
            int index = 1;
            for (Map<String, Object> section : sections)
            {
                String sectionTitle = section.get("title") != null ? String.valueOf(section.get("title")) : ("Section " + index);
                String sectionKey = section.get("key") != null ? String.valueOf(section.get("key")) : "";
                addConsentSectionHeader(doc, font, index, sectionTitle);
                Object paragraphsObj = section.get("paragraphs");
                if (paragraphsObj instanceof List<?>)
                {
                    for (Object item : (List<?>) paragraphsObj)
                    {
                        if (item != null && !String.valueOf(item).trim().isEmpty())
                        {
                            doc.add(new Paragraph(String.valueOf(item).trim()).setFont(font).setFontSize(10));
                        }
                    }
                }
                addConsentAcknowledgement(doc, font, isConsentAcknowledged(consentAcknowledgements, sectionKey));
                index++;
            }

            addClinicSeal(doc);
            addConsentSignatureCard(doc, font, displaySignature, signedAt);
            doc.close();
            hospitalFileStorage.backupResource(resourcePath);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("Failed to generate consent PDF", e);
            throw new ServiceException("PDF generation failed: " + e.getMessage());
        }

        Map<String, String> result = buildResult(resourcePath);
        updatePatientConsentMeta(patient, result);
        insertConsentFileRecord(patient, resourcePath);
        return result;
    }

    private List<Map<String, Object>> extractConsentSections(Object sectionsObj, Object configuredConsentTemplate)
    {
        if (sectionsObj instanceof List<?>)
        {
            List<Map<String, Object>> sections = new java.util.ArrayList<>();
            for (Object item : (List<?>) sectionsObj)
            {
                if (item instanceof Map<?, ?>)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> section = (Map<String, Object>) item;
                    sections.add(section);
                }
                else if (item instanceof JSONObject)
                {
                    sections.add(new HashMap<>((JSONObject) item));
                }
            }
            if (!sections.isEmpty())
            {
                return sections;
            }
        }
        return ConsentDocumentTemplate.toResponseSections(configuredConsentTemplate);
    }

    private boolean isConsentAcknowledged(JSONObject acknowledgements, String sectionKey)
    {
        if (acknowledgements == null || acknowledgements.isEmpty())
        {
            return true;
        }
        if (StringUtils.isBlank(sectionKey))
        {
            return false;
        }
        return acknowledgements.getBooleanValue(sectionKey);
    }

    private void addConsentSectionHeader(Document doc, PdfFont font, int index, String title)
    {
        Table header = new Table(UnitValue.createPercentArray(new float[] { 0.4f, 5f })).useAllAvailableWidth();
        header.setMarginTop(8).setMarginBottom(4);
        header.addCell(new Cell()
                .add(new Paragraph(String.valueOf(index)).setFont(font).setFontSize(11).setBold()
                        .setFontColor(ColorConstants.WHITE).setTextAlignment(TextAlignment.CENTER))
                .setBackgroundColor(PRIMARY_COLOR)
                .setBorder(Border.NO_BORDER)
                .setPadding(6));
        header.addCell(new Cell()
                .add(new Paragraph(title).setFont(font).setFontSize(11).setBold().setFontColor(PRIMARY_COLOR))
                .setBackgroundColor(new DeviceRgb(248, 251, 248))
                .setBorder(new SolidBorder(new DeviceRgb(220, 232, 224), 1))
                .setPadding(6));
        doc.add(header);
    }

    private void addConsentAcknowledgement(Document doc, PdfFont font, boolean agreed)
    {
        Table table = new Table(UnitValue.createPercentArray(new float[] { 1f })).useAllAvailableWidth();
        table.setMarginTop(6).setMarginBottom(6);
        String acknowledgementText = (agreed ? "[v]" : "[ ]") + " I read and agree. / 我已阅读并同意";
        table.addCell(new Cell()
                .add(new Paragraph(acknowledgementText).setFont(font).setFontSize(10).setBold()
                        .setFontColor(new DeviceRgb(39, 68, 55)))
                .setBorder(new SolidBorder(PRIMARY_COLOR, 1))
                .setBackgroundColor(agreed ? new DeviceRgb(232, 244, 237) : ColorConstants.WHITE)
                .setPadding(6));
        doc.add(table);
    }

    private void addConsentSignatureCard(Document doc, PdfFont font, String displaySignature, String signedAt)
    {
        PdfFont signatureFont = createSignatureFont(displaySignature, font);
        Table signature = new Table(UnitValue.createPercentArray(new float[] { 1 })).useAllAvailableWidth();
        signature.setMarginTop(28).setMarginBottom(4).setKeepTogether(true);
        signature.addCell(new Cell()
                .add(new Paragraph("Signature / 签名").setFont(font).setFontSize(9).setBold().setFontColor(MUTED_TEXT)
                        .setTextAlignment(TextAlignment.RIGHT))
                .setBorder(Border.NO_BORDER)
                .setPadding(0));
        signature.addCell(new Cell()
                .add(new Paragraph(safeValue(displaySignature)).setFont(signatureFont).setFontSize(28)
                        .setFontColor(PRIMARY_COLOR).setTextAlignment(TextAlignment.RIGHT))
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(PRIMARY_COLOR, 0.8f))
                .setPadding(4));
        signature.addCell(new Cell()
                .add(new Paragraph("Date / 日期时间: " + safeValue(signedAt)).setFont(font).setFontSize(10)
                        .setFontColor(MUTED_TEXT).setTextAlignment(TextAlignment.RIGHT))
                .setBorder(Border.NO_BORDER)
                .setPaddingTop(6));
        doc.add(signature);
    }

    private Map<String, String> buildResult(String resourcePath)
    {
        Map<String, String> result = new HashMap<>();
        result.put("filePath", resourcePath);
        result.put("resource", resourcePath);
        result.put("url", signedFileUrlService.buildAccessUrl(resourcePath));
        return result;
    }

    private void addInvoiceClinicInfo(Document doc, PdfFont font, String clinicName, String clinicAddress, String clinicPhone, JSONObject practitionerProfile)
    {
        StringBuilder clinic = new StringBuilder();
        clinic.append(clinicName);
        appendLine(clinic, clinicAddress);
        appendLine(clinic, clinicPhone);
        appendLine(clinic, getClinicSetting("clinicEmail"));
        appendLine(clinic, getClinicSetting("clinicOrganization"));
        appendLine(clinic, getClinicSetting("clinicOrganizationNumber"));
        appendLabeledLine(clinic, "HST No.", getClinicSetting("clinicTaxNumber"));
        appendLabeledLine(clinic, "Business No.", getClinicSetting("invoiceBusinessNumber"));

        StringBuilder practitioner = new StringBuilder();
        appendLabeledLine(practitioner, "Practitioner Name", firstNonBlank(
                practitionerProfile.getString("practitionerName"),
                practitionerProfile.getString("name")));
        appendLabeledLine(practitioner, "organisation", firstNonBlank(
                practitionerProfile.getString("organization"),
                practitionerProfile.getString("regulatoryBody")));
        appendLabeledLine(practitioner, "organisation code", firstNonBlank(
                practitionerProfile.getString("organizationNumber"),
                practitionerProfile.getString("registrationNumber"),
                practitionerProfile.getString("licenseNumber")));

        Table table = new Table(UnitValue.createPercentArray(new float[] { 1, 1 })).useAllAvailableWidth();
        table.setMarginBottom(8);
        table.addCell(new Cell()
                .add(new Paragraph(clinic.toString()).setFont(font).setFontSize(10))
                .setBorder(new SolidBorder(BORDER_GREEN, 0.8f))
                .setBackgroundColor(new DeviceRgb(250, 252, 250))
                .setPadding(8));
        table.addCell(new Cell()
                .add(new Paragraph(practitioner.length() > 0 ? practitioner.toString() : "-").setFont(font).setFontSize(10))
                .setBorder(new SolidBorder(BORDER_GREEN, 0.8f))
                .setBackgroundColor(new DeviceRgb(250, 252, 250))
                .setPadding(8));
        doc.add(table);
    }

    private void appendLine(StringBuilder sb, String value)
    {
        if (hasMeaningfulValue(value))
        {
            sb.append("\n").append(value.trim());
        }
    }

    private void appendLabeledLine(StringBuilder sb, String label, String value)
    {
        if (hasMeaningfulValue(value))
        {
            if (sb.length() > 0)
            {
                sb.append("\n");
            }
            sb.append(label).append(": ").append(String.valueOf(value).trim());
        }
    }

    private void addInvoiceBillTo(Document doc, PdfFont font, TcmPatient patient, JSONObject patientPayload)
    {
        if (patient == null) return;
        StringBuilder sb = new StringBuilder("Bill To: ");
        sb.append(safeValue(patient.getName()));
        if (patient.getEmail() != null && !patient.getEmail().isEmpty()) sb.append("  |  ").append(patient.getEmail());
        if (patient.getPhone() != null && !patient.getPhone().isEmpty()) sb.append("  |  ").append(patient.getPhone());
        String street = patientPayload.getString("addressStreet");
        if (street == null || street.isEmpty()) street = patientPayload.getString("address");
        String city = patientPayload.getString("addressCity");
        String state = patientPayload.getString("addressState");
        String postal = patientPayload.getString("addressPostal");
        String country = patientPayload.getString("addressCountry");
        StringBuilder addr = new StringBuilder();
        if (street != null && !street.isEmpty()) addr.append(street);
        if (city != null && !city.isEmpty()) { if (addr.length() > 0) addr.append(", "); addr.append(city); }
        if (state != null && !state.isEmpty()) { if (addr.length() > 0) addr.append(", "); addr.append(state); }
        if (postal != null && !postal.isEmpty()) { if (addr.length() > 0) addr.append(", "); addr.append(postal); }
        if (country != null && !country.isEmpty()) { if (addr.length() > 0) addr.append(", "); addr.append(country); }
        if (addr.length() > 0) sb.append("\n").append(addr);
        doc.add(new Paragraph(sb.toString()).setFont(font).setFontSize(10)
                .setBackgroundColor(new DeviceRgb(249, 249, 249)).setPadding(6).setMarginBottom(8));
    }

    private void addInvoiceConsultationDate(Document doc, PdfFont font, TcmConsultation consultation)
    {
        String consultDate = consultation != null ? safeValue(consultation.getConsultDate()) : "-";
        Table table = new Table(UnitValue.createPercentArray(new float[] { 1f, 2f })).useAllAvailableWidth();
        table.setMarginBottom(8);
        table.addCell(new Cell()
                .add(new Paragraph("Consultation Date / 问诊日期").setFont(font).setFontSize(10).setBold())
                .setBorder(new SolidBorder(BORDER_GREEN, 0.8f))
                .setBackgroundColor(SOFT_GREEN)
                .setPadding(6));
        table.addCell(new Cell()
                .add(new Paragraph(consultDate).setFont(font).setFontSize(10).setBold())
                .setBorder(new SolidBorder(BORDER_GREEN, 0.8f))
                .setPadding(6));
        doc.add(table);
    }

    private void addReportClinicInfo(Document doc, PdfFont font, String clinicName)
    {
        Table table = new Table(UnitValue.createPercentArray(new float[] { 1, 1 })).useAllAvailableWidth();
        addInfoRow(table, font, "Clinic / 诊所", clinicName);
        addInfoRow(table, font, "Clinic Address / 诊所地址", getClinicSetting("clinicAddress"));
        addInfoRow(table, font, "Clinic Phone / 诊所电话", getClinicSetting("clinicPhone"));
        addInfoRow(table, font, "Clinic Email / 诊所邮箱", getClinicSetting("clinicEmail"));
        addInfoRow(table, font, "Clinic Organization / 诊所机构", getClinicSetting("clinicOrganization"));
        addInfoRow(table, font, "Business / HST No.", firstNonBlank(
                getClinicSetting("clinicTaxNumber"),
                getClinicSetting("invoiceBusinessNumber"),
                getClinicSetting("clinicOrganizationNumber")));
        doc.add(table);
    }

    private void addConsultationInfo(Document doc, PdfFont font, TcmConsultation consultation, TcmPatient patient, JSONObject patientPayload)
    {
        Table infoTable = new Table(UnitValue.createPercentArray(new float[] { 1, 1 })).useAllAvailableWidth();
        addInfoRow(infoTable, font, "Consultation ID", consultation.getConsultationId());
        addInfoRow(infoTable, font, "Consultation Date", consultation.getConsultDate());
        addInfoRow(infoTable, font, "Patient Name", patient != null ? patient.getName() : "-");
        addInfoRow(infoTable, font, "Date of Birth", patientPayload != null ? firstNonBlank(
                patientPayload.getString("dateOfBirth"),
                patientPayload.getString("birthDate"),
                patientPayload.getString("dob")) : null);
        addInfoRow(infoTable, font, "Patient Phone", firstNonBlank(
                patient != null ? patient.getPhone() : null,
                patientPayload != null ? patientPayload.getString("phone") : null,
                patientPayload != null ? patientPayload.getString("phoneNumber") : null,
                patientPayload != null ? patientPayload.getString("mobile") : null));
        addInfoRow(infoTable, font, "Patient Email", firstNonBlank(
                patient != null ? patient.getEmail() : null,
                patientPayload != null ? patientPayload.getString("email") : null));
        addInfoRow(infoTable, font, "Patient Address", buildPatientAddress(patientPayload));
        addInfoRow(infoTable, font, "Practitioner ID", consultation.getPractitionerId());
        addInfoRow(infoTable, font, "Status", consultation.getStatus());
        addInfoRow(infoTable, font, "Record Type", "Consultation");
        doc.add(infoTable);
    }

    private void addReportConsultationInfo(Document doc, PdfFont font, TcmConsultation consultation, TcmPatient patient, JSONObject patientPayload)
    {
        Table infoTable = new Table(UnitValue.createPercentArray(new float[] { 1, 1 })).useAllAvailableWidth();
        addInfoRow(infoTable, font, "Name / 姓名", buildPatientDisplayName(patient));
        addInfoRow(infoTable, font, "Gender / 性别", resolvePatientGender(patientPayload));
        addInfoRow(infoTable, font, "Date of Birth / 出生日期", resolvePatientDob(patientPayload));
        addInfoRow(infoTable, font, "Date of Consultation / 问诊日期", consultation.getConsultDate());
        doc.add(infoTable);
    }

    private String buildPatientDisplayName(TcmPatient patient)
    {
        if (patient == null)
        {
            return "-";
        }
        String nameFromParts = firstNonBlank(joinNameParts(patient.getFirstName(), patient.getLastName()));
        return firstNonBlank(nameFromParts, patient.getName());
    }

    private String joinNameParts(String firstName, String lastName)
    {
        List<String> parts = new ArrayList<>();
        if (hasMeaningfulValue(firstName))
        {
            parts.add(firstName.trim());
        }
        if (hasMeaningfulValue(lastName))
        {
            parts.add(lastName.trim());
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private String resolvePatientGender(JSONObject patientPayload)
    {
        if (patientPayload == null)
        {
            return "-";
        }
        return normalizeGender(firstNonBlank(
                patientPayload.getString("gender"),
                patientPayload.getString("sex"),
                patientPayload.getString("genderIdentity")));
    }

    private String resolvePatientDob(JSONObject patientPayload)
    {
        if (patientPayload == null)
        {
            return "-";
        }
        return firstNonBlank(
                patientPayload.getString("dateOfBirth"),
                patientPayload.getString("birthDate"),
                patientPayload.getString("dob"));
    }

    private String buildPatientAddress(JSONObject patientPayload)
    {
        if (patientPayload == null)
        {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        String street = firstNonBlank(
                patientPayload.getString("addressStreet"),
                patientPayload.getString("street"),
                patientPayload.getString("address"));
        if (hasMeaningfulValue(street))
        {
            parts.add(street);
        }
        addAddressPart(parts, patientPayload.getString("addressCity"));
        addAddressPart(parts, patientPayload.getString("city"));
        addAddressPart(parts, patientPayload.getString("addressState"));
        addAddressPart(parts, patientPayload.getString("province"));
        addAddressPart(parts, patientPayload.getString("addressPostal"));
        addAddressPart(parts, patientPayload.getString("postalCode"));
        addAddressPart(parts, patientPayload.getString("addressCountry"));
        addAddressPart(parts, patientPayload.getString("country"));
        if (!parts.isEmpty())
        {
            return String.join(", ", parts);
        }
        return firstNonBlank(patientPayload.getString("fullAddress"), patientPayload.getString("mailingAddress"));
    }

    private void addAddressPart(List<String> parts, String value)
    {
        if (hasMeaningfulValue(value) && !parts.contains(value.trim()))
        {
            parts.add(value.trim());
        }
    }

    private void addReportPractitionerInfo(Document doc, PdfFont font, JSONObject practitionerProfile)
    {
        if (practitionerProfile == null || practitionerProfile.isEmpty())
        {
            return;
        }
        Table table = new Table(UnitValue.createPercentArray(new float[] { 1, 1 })).useAllAvailableWidth();
        addInfoRow(table, font, "Practitioner / 中医师", practitionerProfile.getString("practitionerName"));
        addInfoRow(table, font, "Title / 职称", practitionerProfile.getString("title"));
        addInfoRow(table, font, "Organization / 注册机构", firstNonBlank(
                practitionerProfile.getString("organization"),
                practitionerProfile.getString("regulatoryBody")));
        addInfoRow(table, font, "Organization No. / 注册号", firstNonBlank(
                practitionerProfile.getString("organizationNumber"),
                practitionerProfile.getString("registrationNumber")));
        addInfoRow(table, font, "Practitioner Email / 邮箱", practitionerProfile.getString("practitionerEmail"));
        addInfoRow(table, font, "Practitioner Phone / 电话", practitionerProfile.getString("practitionerPhone"));
        doc.add(table);
    }

    private void addPractitionerReimbursementLine(Document doc, PdfFont font, JSONObject practitionerProfile)
    {
        if (practitionerProfile == null || practitionerProfile.isEmpty())
        {
            return;
        }

        List<String> parts = new ArrayList<>();
        appendLabeledPart(parts, "Practitioner Name / 医师姓名", firstNonBlank(
                practitionerProfile.getString("practitionerName"),
                practitionerProfile.getString("name")));
        appendLabeledPart(parts, "Organization / 组织", firstNonBlank(
                practitionerProfile.getString("organization"),
                practitionerProfile.getString("regulatoryBody")));
        appendLabeledPart(parts, "Organization No. / 组织号码", firstNonBlank(
                practitionerProfile.getString("organizationNumber"),
                practitionerProfile.getString("registrationNumber"),
                practitionerProfile.getString("licenseNumber")));
        if (parts.isEmpty())
        {
            return;
        }

        doc.add(new Paragraph(String.join("  |  ", parts))
                .setFont(font)
                .setFontSize(10)
                .setBold()
                .setFontColor(PRIMARY_COLOR)
                .setBackgroundColor(new DeviceRgb(249, 249, 249))
                .setBorder(new SolidBorder(BORDER_GREEN, 0.8f))
                .setPadding(6)
                .setMarginBottom(8));
    }

    private void appendLabeledPart(List<String> parts, String label, String value)
    {
        String text = cleanText(value);
        if (text != null)
        {
            parts.add(label + ": " + text);
        }
    }

    private void addChiefComplaintSection(Document doc, PdfFont font, JSONObject payload)
    {
        List<String> lines = new ArrayList<>();
        appendKeyValue(lines, "Chief Complaint / 主诉", payload.get("chiefComplaint"));
        appendKeyValue(lines, "Duration / 持续时间", payload.get("chiefComplaintDuration"));
        appendKeyValue(lines, "Description / 描述", payload.get("chiefComplaintDescription"));
        appendKeyValue(lines, "Progress / 病程变化", payload.get("progressOfDisease"));
        lines.clear();
        appendKeyValue(lines, "Chief Complaint / 主诉", payload.get("chiefComplaint"));
        appendKeyValue(lines, "Chief Complaint Duration / 主诉时间", payload.get("chiefComplaintDuration"));
        appendKeyValue(lines, "Chief Complaint Description / 主诉描述", payload.get("chiefComplaintDescription"));
        appendKeyValue(lines, "Progress / 病程变化", payload.get("progressOfDisease"));
        addLinesSection(doc, font, "Chief Complaint / 主诉", lines);
    }

    private void addInitialIntakeSection(Document doc, PdfFont font, JSONObject patientPayload, JSONObject consultationPayload)
    {
        JSONObject latestIntake = toJsonObject(patientPayload != null ? patientPayload.get("latestIntakeFormData") : null);
        if (latestIntake.isEmpty())
        {
            latestIntake = toJsonObject(consultationPayload.get("intakeFormData"));
        }

        List<String> lines = new ArrayList<>();
        appendKeyValue(lines, "Submitted At / 提交时间", patientPayload != null ? patientPayload.get("latestIntakeSubmittedAt") : null);
        appendKeyValue(lines, "Source / 来源", patientPayload != null ? patientPayload.get("latestIntakeSource") : null);

        appendFirstAvailable(lines, "Chief Complaint / 主诉", latestIntake, patientPayload, "chiefComplaint");
        appendFirstAvailable(lines, "Duration / 持续时间", latestIntake, patientPayload, "chiefComplaintDuration");
        appendFirstAvailable(lines, "Description / 描述", latestIntake, patientPayload, "chiefComplaintDescription");
        appendFirstAvailable(lines, "Progress / 病程", latestIntake, patientPayload, "progressOfDisease");
        appendFirstAvailable(lines, "Allergies / 过敏史", latestIntake, patientPayload, "allergies");
        appendFirstAvailable(lines, "Drug Allergies / 药物过敏", latestIntake, patientPayload, "drugAllergies");
        appendFirstAvailable(lines, "Other Allergies / 其他过敏", latestIntake, patientPayload, "otherAllergies");
        appendFirstAvailable(lines, "Medical History / 既往病史", latestIntake, patientPayload, "medicalHistory");
        appendFirstAvailable(lines, "Medical History Selections / 病史勾选", latestIntake, patientPayload, "medicalHistorySelections");
        appendFirstAvailable(lines, "Additional Medical History / 病史补充", latestIntake, patientPayload, "otherMedicalHistory");
        appendFirstAvailable(lines, "Current Medications / 当前用药", latestIntake, patientPayload, "currentMedications");
        appendFirstAvailable(lines, "Medication Selections / 用药勾选", latestIntake, patientPayload, "currentMedicationSelections");
        appendFirstAvailable(lines, "Medication Details / 用药详情", latestIntake, patientPayload, "medicationDetails");
        appendFirstAvailable(lines, "Family History / 家族史", latestIntake, patientPayload, "familyHistory");
        appendFirstAvailable(lines, "Metal Implants / 金属植入部位", latestIntake, patientPayload, "metalImplantsLocation");
        appendFirstAvailable(lines, "Implant Type / 植入物类型", latestIntake, patientPayload, "implantType");
        appendFirstAvailable(lines, "Smoking / 吸烟", latestIntake, patientPayload, "smokingStatus");
        appendFirstAvailable(lines, "Alcohol / 饮酒", latestIntake, patientPayload, "alcoholStatus");
        appendFirstAvailable(lines, "Exercise / 运动", latestIntake, patientPayload, "exerciseStatus");
        appendFirstAvailable(lines, "Lifestyle / 生活方式", latestIntake, patientPayload, "lifestyle");
        appendFirstAvailable(lines, "Lifestyle Notes / 生活方式补充", latestIntake, patientPayload, "lifestyleNotes");
        appendFirstAvailable(lines, "Currently Pregnant / 是否怀孕", latestIntake, patientPayload, "currentlyPregnant");
        appendFirstAvailable(lines, "Breastfeeding / 是否哺乳", latestIntake, patientPayload, "breastfeeding");
        appendFirstAvailable(lines, "Female Health / 女性专项", latestIntake, patientPayload, "femaleHealthSummary");
        appendFirstAvailable(lines, "Additional Notes / 其他补充", latestIntake, patientPayload, "additionalNotes");
        appendFirstAvailable(lines, "Signature / 签名", latestIntake, patientPayload, "signatureName");
        appendFirstAvailable(lines, "Signed Date / 签署日期", latestIntake, patientPayload, "signedDate");

        addLinesSection(doc, font, "Initial Intake / 初诊问诊记录", lines);
    }

    private void addConsultationRecordSection(Document doc, PdfFont font, JSONObject payload)
    {
        List<String> lines = new ArrayList<>();
        appendKeyValue(lines, "History and Medication / 病史与用药", firstMeaningful(
                payload.get("historyAndMedicationSnapshot"),
                payload.get("historyAndMedication"),
                payload.get("medicalHistory")));
        appendKeyValue(lines, "Assessment / 评估", payload.get("assessment"));
        appendKeyValue(lines, "Diagnosis / 诊断", payload.get("diagnosis"));
        appendKeyValue(lines, "Clinical Notes / 临床记录", payload.get("notes"));
        appendKeyValue(lines, "Comments / 备注", payload.get("comments"));
        appendKeyValue(lines, "Previous Treatment Review / 上次治疗反馈", payload.get("previousPrognosisReview"));
        appendKeyValue(lines, "Current Feedback / 本次反馈", payload.get("feedback"));
        addLinesSection(doc, font, "Consultation Record / 问诊记录", lines);
    }

    private void addDifferentiationSection(Document doc, PdfFont font, JSONObject payload)
    {
        JSONObject diff = payload.getJSONObject("diff");
        List<String> lines = new ArrayList<>();
        appendKeyValue(lines, "Differentiation / 辨证", payload.get("differentiation"));

        if (diff != null)
        {
            JSONArray conclusions = diff.getJSONArray("conclusions");
            if (conclusions != null)
            {
                for (int i = 0; i < conclusions.size(); i++)
                {
                    JSONObject conclusion = conclusions.getJSONObject(i);
                    if (conclusion == null)
                    {
                        continue;
                    }
                    List<String> parts = new ArrayList<>();
                    appendValuePart(parts, conclusion.get("name"));
                    if (hasMeaningfulValue(conclusion.get("treatment")))
                    {
                        parts.add("Treatment: " + formatStructuredValue(conclusion.get("treatment")));
                    }
                    if (!parts.isEmpty())
                    {
                        lines.add("Conclusion " + (i + 1) + ": " + String.join("; ", parts));
                    }
                }
            }

            String diffSummary = buildDiffSummary(diff);
            appendKeyValue(lines, "Findings / 四诊信息", diffSummary);
        }

        addLinesSection(doc, font, "Differentiation Conclusion / 辨证结论", lines);
    }

    private void addTongueImage(Document doc, PdfFont font, JSONObject diff)
    {
        if (diff == null)
        {
            return;
        }
        String imageRef = firstNonBlank(
                diff.getString("tongueImageResource"),
                diff.getString("tongueImage"));
        if (!hasMeaningfulValue(imageRef))
        {
            return;
        }
        doc.add(new Paragraph("Tongue Photo / 舌头照片")
                .setFont(font)
                .setFontSize(10)
                .setBold()
                .setFontColor(PRIMARY_COLOR)
                .setMarginTop(8)
                .setMarginBottom(0));
        addImageResource(doc, imageRef, 150, "Tongue image ignored");
    }

    private void addAcupunctureSection(Document doc, PdfFont font, JSONArray acupuncture)
    {
        if (acupuncture == null || acupuncture.isEmpty())
        {
            return;
        }

        Table table = new Table(UnitValue.createPercentArray(new float[] { 2, 1, 2 })).useAllAvailableWidth();
        boolean hasRows = false;
        addTableHeader(table, font, "Point / 穴位", "Side / 侧别", "Notes / 备注");
        for (int i = 0; i < acupuncture.size(); i++)
        {
            JSONObject item = acupuncture.getJSONObject(i);
            if (item == null || !hasAnyMeaningfulValue(item, "point", "side", "notes"))
            {
                continue;
            }
            addTableRow(table, font,
                    formatStructuredValue(item.get("point")),
                    formatStructuredValue(item.get("side")),
                    formatStructuredValue(item.get("notes")));
            hasRows = true;
        }
        if (hasRows)
        {
            addSectionTitle(doc, font, "Acupuncture Points / 针灸选穴");
            doc.add(table);
        }
    }

    private void addTreatmentInfoSection(Document doc, PdfFont font, JSONObject payload)
    {
        List<String> lines = new ArrayList<>();
        appendKeyValue(lines, "Treatment Plan / 治疗方案", firstMeaningful(
                payload.get("treatment"),
                payload.get("treatmentPlan"),
                payload.get("acupunctureTreatment")));
        appendKeyValue(lines, "Prognosis / 预后", payload.get("prognosis"));
        appendKeyValue(lines, "Follow Up / 复诊建议", firstMeaningful(
                payload.get("followUp"),
                payload.get("followUpAdvice"),
                payload.get("aftercare")));

        addLinesSection(doc, font, "Treatment / 治疗方案", lines);
    }

    private void addHerbalSection(Document doc, PdfFont font, JSONArray herbals, JSONObject payload)
    {
        if (herbals == null || herbals.isEmpty())
        {
            return;
        }
        addSectionTitle(doc, font, "Herbal Prescription / 草药处方");
        List<String> headerParts = new ArrayList<>();
        appendKeyValuePart(headerParts, "Formula / 方名", payload.get("formulaName"));
        appendKeyValuePart(headerParts, "Type / 类型", payload.get("prescriptionType"));
        appendKeyValuePart(headerParts, "Direction / 服用方法", firstMeaningful(
                payload.get("direction"),
                payload.get("instructions"),
                payload.get("usage")));
        appendKeyValuePart(headerParts, "Total Doses / 总剂数", firstMeaningful(
                payload.get("quantity"),
                payload.get("totalDoses"),
                payload.get("doseCount")));
        if (!headerParts.isEmpty())
        {
            doc.add(new Paragraph(String.join("    ", headerParts)).setFont(font).setFontSize(10).setBold());
        }
        Table herbTable = new Table(UnitValue.createPercentArray(new float[] { 2, 1, 1 })).useAllAvailableWidth();
        addTableHeader(herbTable, font, "Herb / 草药", "Per Dose / 单剂剂量", "Unit / 单位");
        for (int i = 0; i < herbals.size(); i++)
        {
            JSONObject herb = herbals.getJSONObject(i);
            if (herb == null || !hasAnyMeaningfulValue(herb, "name", "dosage", "dose", "perDose", "singleDose", "unit"))
            {
                continue;
            }
            addTableRow(
                    herbTable,
                    font,
                    formatHerbDisplayName(herb),
                    formatStructuredValue(firstMeaningful(
                            herb.get("dosage"),
                            herb.get("dose"),
                            herb.get("perDose"),
                            herb.get("singleDose"))),
                    herb.getString("unit"));
        }
        doc.add(herbTable);
    }

    private void addFormulaCompositionSection(Document doc, PdfFont font, JSONObject payload)
    {
        JSONArray prescriptions = payload.getJSONArray("prescriptions");
        boolean added = false;
        if (prescriptions != null && !prescriptions.isEmpty())
        {
            for (int i = 0; i < prescriptions.size(); i++)
            {
                JSONObject rx = prescriptions.getJSONObject(i);
                if (rx == null || "deleted".equals(rx.getString("rxStatus")) || rx.getString("deletedAt") != null)
                {
                    continue;
                }
                JSONArray items = rx.getJSONArray("items");
                boolean hasItems = items != null && !items.isEmpty();
                if (!hasItems && !hasAnyMeaningfulValue(rx, "formulaName", "prescriptionType", "direction", "whereToGet"))
                {
                    continue;
                }

                if (!added)
                {
                    addSectionTitle(doc, font, "Herbal Prescription / 草药处方");
                    added = true;
                }

                List<String> headerParts = new ArrayList<>();
                appendKeyValuePart(headerParts, "Formula / 方名", firstMeaningful(rx.get("formulaName"), rx.get("name")));
                appendKeyValuePart(headerParts, "Type / 类型", rx.get("prescriptionType"));
                appendKeyValuePart(headerParts, "Direction / 服用方法", firstMeaningful(
                        rx.get("direction"),
                        rx.get("instructions"),
                        rx.get("usage")));
                appendKeyValuePart(headerParts, "Where To Get / 取药地点", rx.get("whereToGet"));
                appendKeyValuePart(headerParts, "Total Doses / 总剂数", firstMeaningful(
                        rx.get("quantity"),
                        rx.get("totalDoses"),
                        rx.get("doseCount")));
                if (!headerParts.isEmpty())
                {
                    doc.add(new Paragraph(String.join("    ", headerParts)).setFont(font).setFontSize(10).setBold());
                }

                if (hasItems)
                {
                    Table itemTable = new Table(UnitValue.createPercentArray(new float[] { 2, 1, 1, 2 })).useAllAvailableWidth();
                    addTableHeader(itemTable, font, "Herb / 草药", "Per Dose / 单剂剂量", "Unit / 单位", "Notes / 备注");
                    boolean hasRows = false;
                    for (int j = 0; j < items.size(); j++)
                    {
                        JSONObject herb = items.getJSONObject(j);
                        if (herb == null || !hasAnyMeaningfulValue(herb, "name", "dosage", "unit", "notes"))
                        {
                            continue;
                        }
                        addTableRow(
                                itemTable,
                                font,
                                formatHerbDisplayName(herb),
                                formatStructuredValue(firstMeaningful(
                                        herb.get("dosage"),
                                        herb.get("dose"),
                                        herb.get("perDose"),
                                        herb.get("singleDose"))),
                                formatStructuredValue(herb.get("unit")),
                                firstNonBlank(
                                        formatStructuredValue(herb.get("notes")),
                                        formatStructuredValue(herb.get("supplierName")),
                                        formatStructuredValue(herb.get("category"))));
                        hasRows = true;
                    }
                    if (hasRows)
                    {
                        doc.add(itemTable);
                    }
                }
            }
        }

        if (!added)
        {
            addHerbalSection(doc, font, payload.getJSONArray("herbals"), payload);
        }
    }

    private void addPrescriptionSummary(Document doc, PdfFont font, JSONArray prescriptions)
    {
        if (prescriptions == null || prescriptions.isEmpty())
        {
            return;
        }
        addSectionTitle(doc, font, "Prescriptions");
        Table table = new Table(UnitValue.createPercentArray(new float[] { 2, 1, 1, 1 })).useAllAvailableWidth();
        addTableHeader(table, font, "Formula", "Type", "Qty", "Status");
        for (int i = 0; i < prescriptions.size(); i++)
        {
            JSONObject rx = prescriptions.getJSONObject(i);
            if (rx == null || "deleted".equals(rx.getString("rxStatus")) || rx.getString("deletedAt") != null)
            {
                continue;
            }
            addTableRow(
                    table,
                    font,
                    firstNonBlank(rx.getString("formulaName"), rx.getString("name"), "-"),
                    safeValue(rx.getString("prescriptionType")),
                    String.valueOf(rx.getIntValue("quantity", 1)),
                    safeValue(rx.getString("rxStatus")));
        }
        doc.add(table);
    }

    private void addInvoiceItems(Document doc, PdfFont font, JSONObject payload, String currency)
    {
        JSONArray services = payload.getJSONArray("services");
        Table serviceTable = new Table(UnitValue.createPercentArray(new float[] { 3, 1, 1, 1 })).useAllAvailableWidth();
        addTableHeader(serviceTable, font, "Description / 收费项目", "Qty / 数量", "Unit Price / 单价", "Amount / 金额");
        boolean hasRows = false;

        BigDecimal consultationFee = payload.getBigDecimal("consultationFee");
        if (isPositive(consultationFee))
        {
            addTableRow(serviceTable, font,
                    "Consultation Fee / 诊疗费",
                    "1",
                    formatMoney(consultationFee, currency),
                    formatMoney(consultationFee, currency));
            hasRows = true;
        }

        if (services != null && !services.isEmpty())
        {
            for (int i = 0; i < services.size(); i++)
            {
                JSONObject service = services.getJSONObject(i);
                if (service == null || !hasAnyMeaningfulValue(service, "name", "price", "quantity", "manualDiscount"))
                {
                    continue;
                }
                BigDecimal price = defaultMoney(service.getBigDecimal("price"));
                int qty = Math.max(1, service.getIntValue("quantity", 1));
                BigDecimal discount = defaultMoney(service.getBigDecimal("manualDiscount"));
                BigDecimal subtotal = price.multiply(BigDecimal.valueOf(qty)).subtract(discount).max(BigDecimal.ZERO);
                addTableRow(
                        serviceTable,
                        font,
                        firstNonBlank(service.getString("name"), "Service / 服务"),
                        String.valueOf(qty),
                        formatMoney(price, currency),
                        formatMoney(subtotal, currency));
                hasRows = true;
            }
        }

        if (payload.getBooleanValue("includeRxAmount"))
        {
            JSONArray prescriptions = payload.getJSONArray("prescriptions");
            if (prescriptions != null && !prescriptions.isEmpty())
            {
                for (int p = 0; p < prescriptions.size(); p++)
                {
                    JSONObject rx = prescriptions.getJSONObject(p);
                    if (!isBillablePrescription(rx))
                    {
                        continue;
                    }
                    int rxQty = Math.max(1, rx.getIntValue("quantity", 1));
                    BigDecimal subtotal = defaultMoney(rx.getBigDecimal("subtotal"));
                    BigDecimal unitPrice = rx.getBigDecimal("perDoseSubtotal");
                    if (unitPrice == null && subtotal.compareTo(BigDecimal.ZERO) > 0)
                    {
                        unitPrice = subtotal.divide(BigDecimal.valueOf(rxQty), 2, RoundingMode.HALF_UP);
                    }
                    String rxName = firstNonBlank(
                            rx.getString("formulaName"),
                            rx.getString("name"),
                            rx.getString("prescriptionType"),
                            "Prescription / 中药");
                    addTableRow(
                            serviceTable,
                            font,
                            "Prescription / 中药: " + rxName,
                            String.valueOf(rxQty),
                            formatMoney(unitPrice, currency),
                            formatMoney(subtotal, currency));
                    hasRows = true;
                }
            }
        }

        if (!hasRows)
        {
            addTableRow(serviceTable, font, "No charge items / 暂无收费项目", "-", "-", formatMoney(BigDecimal.ZERO, currency));
        }
        doc.add(serviceTable);
    }

    private boolean isBillablePrescription(JSONObject rx)
    {
        if (rx == null || "deleted".equals(rx.getString("rxStatus")) || rx.getString("deletedAt") != null)
        {
            return false;
        }
        String rxStatus = rx.getString("rxStatus");
        return StringUtils.isBlank(rxStatus) || "pending".equals(rxStatus) || "dispensed".equals(rxStatus);
    }

    private void addInvoiceTotals(Document doc, PdfFont font, JSONObject payload, String currency)
    {
        doc.add(new Paragraph("\n").setFontSize(4));
        BigDecimal totalAmount = defaultMoney(payload.getBigDecimal("totalAmount"));
        BigDecimal taxAmount = defaultMoney(payload.getBigDecimal("taxAmount"));
        BigDecimal subtotal = payload.getBigDecimal("totalWithoutTax");
        if (subtotal == null)
        {
            subtotal = totalAmount.subtract(taxAmount).max(BigDecimal.ZERO);
        }
        BigDecimal paidAmount = resolvePaidAmount(payload);
        BigDecimal balanceAmount = totalAmount.subtract(paidAmount).max(BigDecimal.ZERO);

        Table totalTable = new Table(UnitValue.createPercentArray(new float[] { 3, 1 })).useAllAvailableWidth();
        totalTable.addCell(new Cell().add(new Paragraph("Subtotal").setFont(font).setFontSize(10))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT));
        totalTable.addCell(new Cell().add(new Paragraph(formatMoney(subtotal, currency))
                .setFont(font)
                .setFontSize(10))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT));
        totalTable.addCell(new Cell().add(new Paragraph(resolveHstLabel(payload)).setFont(font).setFontSize(10))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT));
        totalTable.addCell(new Cell().add(new Paragraph(formatMoney(taxAmount, currency)).setFont(font).setFontSize(10))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT));
        totalTable.addCell(new Cell().add(new Paragraph("Total").setFont(font).setFontSize(14).setBold()
                .setFontColor(PRIMARY_COLOR))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT));
        totalTable.addCell(new Cell().add(new Paragraph(formatMoney(totalAmount, currency)).setFont(font)
                .setFontSize(14)
                .setBold()
                .setFontColor(PRIMARY_COLOR))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT));
        totalTable.addCell(new Cell().add(new Paragraph("Paid / 已付").setFont(font).setFontSize(10))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT));
        totalTable.addCell(new Cell().add(new Paragraph(formatMoney(paidAmount, currency)).setFont(font).setFontSize(10))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT));
        totalTable.addCell(new Cell().add(new Paragraph("Balance / 待付余额").setFont(font).setFontSize(10).setBold())
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT));
        totalTable.addCell(new Cell().add(new Paragraph(formatMoney(balanceAmount, currency)).setFont(font).setFontSize(10).setBold())
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT));
        doc.add(totalTable);
    }

    private BigDecimal resolvePaidAmount(JSONObject payload)
    {
        BigDecimal paidAmount = payload.getBigDecimal("paidAmount");
        if (paidAmount != null)
        {
            return paidAmount;
        }
        JSONArray paymentRecords = payload.getJSONArray("paymentRecords");
        BigDecimal total = BigDecimal.ZERO;
        if (paymentRecords != null)
        {
            for (int i = 0; i < paymentRecords.size(); i++)
            {
                JSONObject record = paymentRecords.getJSONObject(i);
                if (record != null)
                {
                    total = total.add(defaultMoney(record.getBigDecimal("amount")));
                }
            }
        }
        return total;
    }

    private String resolveHstLabel(JSONObject payload)
    {
        BigDecimal rate = payload != null ? payload.getBigDecimal("overrideTaxRate") : null;
        if (rate == null)
        {
            rate = parseDecimal(getClinicSetting("taxRate"));
        }
        if (rate == null)
        {
            return "HST";
        }
        BigDecimal percent = rate.compareTo(BigDecimal.ONE) > 0
                ? rate
                : rate.multiply(BigDecimal.valueOf(100));
        return "HST (" + percent.stripTrailingZeros().toPlainString() + "%)";
    }

    private BigDecimal parseDecimal(String value)
    {
        if (!hasMeaningfulValue(value))
        {
            return null;
        }
        try
        {
            return new BigDecimal(value.trim());
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private boolean isPositive(BigDecimal amount)
    {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal defaultMoney(BigDecimal amount)
    {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    private String resolveCurrency(JSONObject payload)
    {
        return "CAD";
    }

    private String formatMoney(BigDecimal amount, String currency)
    {
        BigDecimal safeAmount = amount != null ? amount : BigDecimal.ZERO;
        return "CAD " + safeAmount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void addParagraphSection(Document doc, PdfFont font, String title, String content)
    {
        addSectionTitle(doc, font, title);
        doc.add(new Paragraph(safeContent(content)).setFont(font).setFontSize(10));
    }

    private void addLinesSection(Document doc, PdfFont font, String title, List<String> lines)
    {
        if (lines == null || lines.isEmpty())
        {
            return;
        }
        addParagraphSection(doc, font, title, String.join("\n", lines));
    }

    private void addOptionalSection(Document doc, PdfFont font, String title, String content)
    {
        if (!hasMeaningfulValue(content))
        {
            return;
        }
        addParagraphSection(doc, font, title, content);
    }

    private void addOptionalParagraph(Document doc, PdfFont font, String content)
    {
        if (!hasMeaningfulValue(content))
        {
            return;
        }
        doc.add(new Paragraph(content).setFont(font).setFontSize(10));
    }

    private String buildDiffSummary(JSONObject diff)
    {
        if (diff == null || diff.isEmpty())
        {
            return "-";
        }
        Map<String, String> labels = diffLabels();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : labels.entrySet())
        {
            appendDiffLine(lines, entry.getValue(), diff.get(entry.getKey()));
        }
        for (String key : diff.keySet())
        {
            if (labels.containsKey(key)
                    || "conclusions".equals(key)
                    || "tongueImage".equals(key)
                    || "tongueImageResource".equals(key))
            {
                continue;
            }
            appendDiffLine(lines, humanizeKey(key), diff.get(key));
        }
        return lines.isEmpty() ? "-" : String.join("\n", lines);
    }

    private Map<String, String> diffLabels()
    {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("coldHeat", "Cold/Heat / 寒热");
        labels.put("sweat", "Sweat / 汗");
        labels.put("headDiscomfort", "Head Discomfort / 头部不适");
        labels.put("headPosition", "Head Position / 头痛部位");
        labels.put("eye", "Eye / 眼");
        labels.put("ear", "Ear / 耳");
        labels.put("nose", "Nose / 鼻");
        labels.put("mouth", "Mouth / 口");
        labels.put("taste", "Taste / 口味");
        labels.put("bodyDiscomforts", "Body Discomforts / 身体不适");
        labels.put("bodyDiscomfortsLocation", "Body Location / 身体部位");
        labels.put("skinIssues", "Skin / 皮肤");
        labels.put("otherExterior", "Practitioner Observation & Exterior Symptoms / 医师观察和体表症状");
        labels.put("chest", "Chest / 胸");
        labels.put("hypochondriac", "Hypochondriac / 两胁");
        labels.put("sleep", "Sleep / 睡眠");
        labels.put("anxietyStress", "Anxiety/Stress / 焦虑压力");
        labels.put("otherChest", "Other Chest / 其他心胸");
        labels.put("appetite", "Appetite / 胃口");
        labels.put("thirst", "Thirst / 口渴");
        labels.put("abdomen", "Abdomen / 腹部");
        labels.put("otherAbdomen", "Other Abdomen / 其他腹部");
        labels.put("bowelMovement", "Bowel Movement / 大便");
        labels.put("urine", "Urine / 小便");
        labels.put("otherLowerAbdomen", "Other Lower Abdomen / 其他下腹");
        labels.put("periodCircle", "Period Cycle / 经期长");
        labels.put("periodDuration", "Period Duration / 每期持续");
        labels.put("bloodQuality", "Blood Quality / 经血");
        labels.put("pms", "PMS / 经前症状");
        labels.put("otherFemale", "Other Female / 其他妇科");
        labels.put("pulse", "Pulse / 脉");
        labels.put("pulseRightHand", "Right Pulse / 右手脉");
        labels.put("pulseLeftHand", "Left Pulse / 左手脉");
        labels.put("pulseBothCun", "Both Cun / 双寸脉");
        labels.put("pulseBothGuan", "Both Guan / 双关脉");
        labels.put("pulseBothChi", "Both Chi / 双尺脉");
        labels.put("detailedPulse", "Detailed Pulse / 脉象补充");
        labels.put("pathologicalChannel", "Pathological Channel / 病变经络");
        labels.put("pathologicalChanges", "Pathological Changes / 病变详情");
        labels.put("tongueColor", "Tongue Color / 舌色");
        labels.put("tongueBody", "Tongue Body / 舌体");
        labels.put("tongueCoating", "Tongue Coating / 舌苔");
        labels.put("otherTongue", "Other Tongue / 其他舌象");
        return labels;
    }

    private void appendDiffLine(List<String> lines, String label, Object value)
    {
        appendKeyValue(lines, label, value);
    }

    private void appendFirstAvailable(List<String> lines, String label, JSONObject primary, JSONObject fallback, String key)
    {
        Object value = primary != null ? primary.get(key) : null;
        if (!hasMeaningfulValue(value) && fallback != null)
        {
            value = fallback.get(key);
        }
        appendKeyValue(lines, label, value);
    }

    private void appendKeyValue(List<String> lines, String label, Object value)
    {
        if (!hasMeaningfulValue(value))
        {
            return;
        }
        lines.add(label + ": " + formatStructuredValue(value));
    }

    private void appendValuePart(List<String> parts, Object value)
    {
        if (hasMeaningfulValue(value))
        {
            parts.add(formatStructuredValue(value));
        }
    }

    private void appendKeyValuePart(List<String> parts, String label, Object value)
    {
        if (hasMeaningfulValue(value))
        {
            parts.add(label + ": " + formatStructuredValue(value));
        }
    }

    private Object firstMeaningful(Object... values)
    {
        if (values == null)
        {
            return null;
        }
        for (Object value : values)
        {
            if (hasMeaningfulValue(value))
            {
                return value;
            }
        }
        return null;
    }

    private boolean hasAnyMeaningfulValue(JSONObject object, String... keys)
    {
        if (object == null || keys == null)
        {
            return false;
        }
        for (String key : keys)
        {
            if (hasMeaningfulValue(object.get(key)))
            {
                return true;
            }
        }
        return false;
    }

    private boolean hasMeaningfulValue(Object value)
    {
        if (value == null)
        {
            return false;
        }
        if (value instanceof String)
        {
            String text = ((String) value).trim();
            return !text.isEmpty() && !"-".equals(text) && !"[]".equals(text) && !"{}".equals(text);
        }
        if (value instanceof JSONArray)
        {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.size(); i++)
            {
                if (hasMeaningfulValue(array.get(i)))
                {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?>)
        {
            for (Object item : (Collection<?>) value)
            {
                if (hasMeaningfulValue(item))
                {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof JSONObject)
        {
            JSONObject object = (JSONObject) value;
            for (String key : object.keySet())
            {
                if (hasMeaningfulValue(object.get(key)))
                {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Map<?, ?>)
        {
            for (Object item : ((Map<?, ?>) value).values())
            {
                if (hasMeaningfulValue(item))
                {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private String formatStructuredValue(Object value)
    {
        if (!hasMeaningfulValue(value))
        {
            return "-";
        }
        if (value instanceof JSONArray)
        {
            JSONArray array = (JSONArray) value;
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < array.size(); i++)
            {
                String item = formatStructuredValue(array.get(i));
                if (hasMeaningfulValue(item))
                {
                    parts.add(item);
                }
            }
            return String.join(", ", parts);
        }
        if (value instanceof Collection<?>)
        {
            List<String> parts = new ArrayList<>();
            for (Object item : (Collection<?>) value)
            {
                String text = formatStructuredValue(item);
                if (hasMeaningfulValue(text))
                {
                    parts.add(text);
                }
            }
            return String.join(", ", parts);
        }
        if (value instanceof JSONObject)
        {
            JSONObject object = (JSONObject) value;
            List<String> lines = new ArrayList<>();
            for (String key : object.keySet())
            {
                Object item = object.get(key);
                if (hasMeaningfulValue(item))
                {
                    lines.add(humanizeKey(key) + ": " + formatStructuredValue(item));
                }
            }
            return String.join("\n", lines);
        }
        if (value instanceof Map<?, ?>)
        {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
            {
                Object item = entry.getValue();
                if (hasMeaningfulValue(item))
                {
                    lines.add(humanizeKey(String.valueOf(entry.getKey())) + ": " + formatStructuredValue(item));
                }
            }
            return String.join("\n", lines);
        }
        return String.valueOf(value).trim();
    }

    private JSONObject toJsonObject(Object value)
    {
        if (value instanceof JSONObject)
        {
            return JSON.parseObject(((JSONObject) value).toJSONString());
        }
        if (value instanceof Map<?, ?>)
        {
            return JSON.parseObject(JSON.toJSONString(value));
        }
        if (value instanceof String && StringUtils.isNotBlank((String) value))
        {
            try
            {
                return JSON.parseObject((String) value);
            }
            catch (Exception ignored)
            {
                return new JSONObject(new LinkedHashMap<>());
            }
        }
        return new JSONObject(new LinkedHashMap<>());
    }

    private String humanizeKey(String key)
    {
        if (key == null || key.trim().isEmpty())
        {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        char[] chars = key.trim().toCharArray();
        for (int i = 0; i < chars.length; i++)
        {
            char ch = chars[i];
            if (i > 0 && Character.isUpperCase(ch) && chars[i - 1] != ' ')
            {
                builder.append(' ');
            }
            builder.append(i == 0 ? Character.toUpperCase(ch) : ch);
        }
        return builder.toString();
    }

    private PdfFont createFont()
    {
        try
        {
            String[] fontPaths = {
                "C:/Windows/Fonts/msyh.ttc,0",
                "C:/Windows/Fonts/simsun.ttc,0",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc,0",
            };
            for (String fontPath : fontPaths)
            {
                try
                {
                    String path = fontPath.contains(",") ? fontPath.substring(0, fontPath.indexOf(',')) : fontPath;
                    if (new File(path).exists())
                    {
                        return PdfFontFactory.createFont(fontPath, "Identity-H");
                    }
                }
                catch (Exception ignored)
                {
                    // try next font
                }
            }
            return PdfFontFactory.createFont();
        }
        catch (Exception e)
        {
            throw new ServiceException("failed to load PDF font");
        }
    }

    private PdfFont createSignatureFont(String displaySignature, PdfFont fallback)
    {
        boolean hasCjk = displaySignature != null && displaySignature.chars().anyMatch(ch -> ch > 127);
        String[] fontPaths = hasCjk
                ? new String[] {
                    "C:/Windows/Fonts/simkai.ttf",
                    "C:/Windows/Fonts/STKAITI.TTF",
                    "C:/Windows/Fonts/msyh.ttc,0",
                }
                : new String[] {
                    "C:/Windows/Fonts/segoesc.ttf",
                    "C:/Windows/Fonts/segoescb.ttf",
                    "C:/Windows/Fonts/brushsci.ttf",
                    "C:/Windows/Fonts/ITCEDSCR.TTF",
                    "C:/Windows/Fonts/msyh.ttc,0",
                };
        for (String fontPath : fontPaths)
        {
            try
            {
                String path = fontPath.contains(",") ? fontPath.substring(0, fontPath.indexOf(',')) : fontPath;
                if (new File(path).exists())
                {
                    return PdfFontFactory.createFont(fontPath, "Identity-H");
                }
            }
            catch (Exception ignored)
            {
                // try next font
            }
        }
        return fallback;
    }

    private void addHeader(Document doc, PdfFont font, String clinicName, String subtitle)
    {
        Table header = new Table(UnitValue.createPercentArray(new float[] { 1 })).useAllAvailableWidth();
        header.setMarginBottom(14);
        header.addCell(new Cell()
                .add(new Paragraph(safeValue(clinicName)).setFont(font).setFontSize(18).setBold()
                        .setFontColor(ColorConstants.WHITE))
                .add(new Paragraph(safeValue(subtitle)).setFont(font).setFontSize(11)
                        .setFontColor(new DeviceRgb(225, 240, 231)))
                .setBackgroundColor(PRIMARY_COLOR)
                .setBorder(Border.NO_BORDER)
                .setPadding(14));
        doc.add(header);
    }

    private void addSectionTitle(Document doc, PdfFont font, String title)
    {
        doc.add(new Paragraph(title).setFont(font).setFontSize(12).setBold().setFontColor(PRIMARY_COLOR)
                .setMarginTop(12).setMarginBottom(6)
                .setBackgroundColor(SOFT_GREEN)
                .setBorderLeft(new SolidBorder(PRIMARY_COLOR, 4))
                .setBorderBottom(new SolidBorder(BORDER_GREEN, 1))
                .setPaddingLeft(8)
                .setPaddingTop(6)
                .setPaddingBottom(6));
    }

    private void addInfoRow(Table table, PdfFont font, String label, String value)
    {
        table.addCell(new Cell()
                .add(new Paragraph(label).setFont(font).setFontSize(8).setBold().setFontColor(MUTED_TEXT))
                .add(new Paragraph(safeValue(value)).setFont(font).setFontSize(10))
                .setBorder(new SolidBorder(BORDER_GREEN, 0.6f))
                .setPadding(7));
    }

    private void addTableHeader(Table table, PdfFont font, String... headers)
    {
        for (String header : headers)
        {
            table.addHeaderCell(new Cell().add(new Paragraph(header).setFont(font).setFontSize(9).setBold()
                            .setFontColor(ColorConstants.WHITE))
                    .setBackgroundColor(PRIMARY_COLOR)
                    .setBorder(new SolidBorder(PRIMARY_COLOR, 0.6f))
                    .setPadding(6));
        }
    }

    private void addTableRow(Table table, PdfFont font, String... values)
    {
        for (String value : values)
        {
            table.addCell(new Cell().add(new Paragraph(safeValue(value)).setFont(font).setFontSize(9))
                    .setBorder(new SolidBorder(BORDER_GREEN, 0.6f))
                    .setPadding(6));
        }
    }

    private void addFooter(Document doc, PdfFont font)
    {
        doc.add(new Paragraph("Generated by OTCM Acupuncture Clinic -")
                .setFont(font)
                .setFontSize(8)
                .setFontColor(MUTED_TEXT)
                .setBorderTop(new SolidBorder(BORDER_GREEN, 0.8f))
                .setPaddingTop(8)
                .setMarginTop(18)
                .setTextAlignment(TextAlignment.CENTER));
    }

    private void addConfiguredFooterImage(Document doc)
    {
        String imageRef = resolveThirdPartySignatureRef();
        addImageResource(doc, imageRef, REPORT_IMAGE_MAX_HEIGHT, REPORT_IMAGE_MAX_WIDTH, "Invoice footer image ignored");
    }

    private void addClinicSeal(Document doc)
    {
        addImageResource(doc, resolveClinicSealRef(), REPORT_IMAGE_MAX_HEIGHT, REPORT_IMAGE_MAX_WIDTH, "Clinic seal ignored");
    }

    private void addPractitionerSignature(Document doc, PdfFont font, JSONObject practitionerProfile)
    {
        String imageRef = resolvePractitionerSignatureRef(practitionerProfile);
        if (StringUtils.isBlank(imageRef))
        {
            return;
        }
        addSectionTitle(doc, font, "Practitioner Signature / 医师签名");
        addImageResource(doc, imageRef, REPORT_IMAGE_MAX_HEIGHT, REPORT_IMAGE_MAX_WIDTH, "Practitioner signature ignored");
    }

    private void addInvoiceSignatureSection(Document doc, PdfFont font, JSONObject practitionerProfile, JSONObject payload)
    {
        List<SignatureBlock> blocks = new ArrayList<>();
        String practitionerSignature = resolvePractitionerSignatureRef(practitionerProfile);
        if (StringUtils.isNotBlank(practitionerSignature))
        {
            blocks.add(new SignatureBlock("Practitioner Signature / 医师签名", practitionerSignature, INVOICE_IMAGE_MAX_HEIGHT,
                    "Practitioner signature ignored"));
        }
        String clinicSeal = resolveClinicSealRef();
        if (StringUtils.isNotBlank(clinicSeal))
        {
            blocks.add(new SignatureBlock("Clinic Seal / 诊所印章", clinicSeal, INVOICE_IMAGE_MAX_HEIGHT, "Clinic seal ignored"));
        }
        if (payload != null && payload.getBooleanValue("add3rdParty"))
        {
            String thirdPartySignature = resolveThirdPartySignatureRef();
            if (StringUtils.isNotBlank(thirdPartySignature))
            {
                blocks.add(new SignatureBlock("3rd Party Signature / 第三方签名", thirdPartySignature, INVOICE_IMAGE_MAX_HEIGHT,
                        "Third party signature ignored"));
            }
        }
        if (blocks.isEmpty())
        {
            return;
        }

        addSectionTitle(doc, font, "Signatures / 签名与印章");
        float[] widths = new float[blocks.size()];
        for (int i = 0; i < widths.length; i++)
        {
            widths[i] = 1f;
        }
        Table table = new Table(UnitValue.createPercentArray(widths)).useAllAvailableWidth();
        for (SignatureBlock block : blocks)
        {
            Cell cell = new Cell()
                    .setBorder(new SolidBorder(BORDER_GREEN, 0.8f))
                    .setPadding(8)
                    .setMinHeight(84);
            cell.add(new Paragraph(block.label)
                    .setFont(font)
                    .setFontSize(9)
                    .setBold()
                    .setFontColor(MUTED_TEXT)
                    .setMarginBottom(6));
            if (!addImageResource(cell, block.imageRef, block.maxHeight, INVOICE_IMAGE_MAX_WIDTH, block.warningPrefix))
            {
                cell.add(new Paragraph("Image unavailable")
                        .setFont(font)
                        .setFontSize(8)
                        .setFontColor(MUTED_TEXT));
            }
            table.addCell(cell);
        }
        doc.add(table);
    }

    private String resolvePractitionerSignatureRef(JSONObject practitionerProfile)
    {
        if (practitionerProfile == null)
        {
            return "";
        }
        Object signature = practitionerProfile.get("signature");
        String imageRef = "";
        if (signature instanceof JSONObject)
        {
            imageRef = safeStr((JSONObject) signature, "path");
        }
        if (StringUtils.isBlank(imageRef))
        {
            imageRef = firstNonBlank(
                    practitionerProfile.getString("signaturePath"),
                    practitionerProfile.getString("signaturePng"),
                    practitionerProfile.getString("signatureUrl"));
        }
        return imageRef;
    }

    private String resolveClinicSealRef()
    {
        JSONObject clinicSeal = parsePayload(getClinicSetting("clinicSeal"));
        String imageRef = safeStr(clinicSeal, "path");
        if (StringUtils.isNotBlank(imageRef))
        {
            return imageRef;
        }
        return firstNonBlank(
                getClinicSetting("clinicSealPath"),
                getClinicSetting("clinicSealPngPath"),
                getClinicSetting("clinicSealUrl"));
    }

    private String resolveThirdPartySignatureRef()
    {
        JSONObject thirdPartySignature = parsePayload(getClinicSetting("thirdPartySignature"));
        String imageRef = safeStr(thirdPartySignature, "path");
        if (StringUtils.isNotBlank(imageRef))
        {
            return imageRef;
        }
        return firstNonBlank(
                getClinicSetting("invoiceFooterImagePath"),
                getClinicSetting("invoiceFooterPngPath"),
                getClinicSetting("invoiceFooterImageUrl"));
    }

    private void addImageResource(Document doc, String imageRef, float maxHeight, String warningPrefix)
    {
        addImageResource(doc, imageRef, maxHeight, REPORT_IMAGE_MAX_WIDTH, warningPrefix);
    }

    private void addImageResource(Document doc, String imageRef, float maxHeight, float maxWidth, String warningPrefix)
    {
        if (StringUtils.isBlank(imageRef) || "-".equals(imageRef.trim()))
        {
            return;
        }
        try
        {
            String source = resolveImageSource(imageRef);
            Image image = new Image(ImageDataFactory.create(source));
            scaleImageDownToFit(image, maxWidth, maxHeight);
            image.setMarginTop(12);
            image.setTextAlignment(TextAlignment.CENTER);
            doc.add(image);
        }
        catch (Exception e)
        {
            log.warn("{}: {}", warningPrefix, e.getMessage());
        }
    }

    private boolean addImageResource(Cell cell, String imageRef, float maxHeight, String warningPrefix)
    {
        return addImageResource(cell, imageRef, maxHeight, INVOICE_IMAGE_MAX_WIDTH, warningPrefix);
    }

    private boolean addImageResource(Cell cell, String imageRef, float maxHeight, float maxWidth, String warningPrefix)
    {
        if (cell == null || StringUtils.isBlank(imageRef) || "-".equals(imageRef.trim()))
        {
            return false;
        }
        try
        {
            String source = resolveImageSource(imageRef);
            Image image = new Image(ImageDataFactory.create(source));
            scaleImageDownToFit(image, maxWidth, maxHeight);
            image.setMarginTop(6);
            cell.add(image);
            return true;
        }
        catch (Exception e)
        {
            log.warn("{}: {}", warningPrefix, e.getMessage());
            return false;
        }
    }

    private void scaleImageDownToFit(Image image, float maxWidth, float maxHeight)
    {
        if (image == null)
        {
            return;
        }
        if (image.getImageScaledWidth() > maxWidth || image.getImageScaledHeight() > maxHeight)
        {
            image.scaleToFit(maxWidth, maxHeight);
        }
    }

    private String resolveImageSource(String imageRef)
    {
        String source = imageRef.trim();
        if (!source.startsWith("http://") && !source.startsWith("https://") && !new File(source).isAbsolute())
        {
            hospitalFileStorage.restoreResource(source);
            source = hospitalFileStorage.resolve(source).toString();
        }
        return source;
    }

    private JSONObject resolvePractitionerProfile(TcmConsultation consultation)
    {
        JSONObject practitionerProfile = new JSONObject(new LinkedHashMap<>());
        if (consultation != null && consultation.getPractitionerId() != null)
        {
            try
            {
                SysUser practitioner = userService.selectUserById(Long.valueOf(consultation.getPractitionerId()));
                if (practitioner != null)
                {
                    practitionerProfile = parsePayload(practitioner.getRemark());
                    if (StringUtils.isBlank(practitionerProfile.getString("practitionerName"))
                            && StringUtils.isNotBlank(practitioner.getNickName()))
                    {
                        practitionerProfile.put("practitionerName", practitioner.getNickName());
                    }
                    if (StringUtils.isBlank(practitionerProfile.getString("practitionerEmail"))
                            && StringUtils.isNotBlank(practitioner.getEmail()))
                    {
                        practitionerProfile.put("practitionerEmail", practitioner.getEmail());
                    }
                    if (StringUtils.isBlank(practitionerProfile.getString("practitionerPhone"))
                            && StringUtils.isNotBlank(practitioner.getPhonenumber()))
                    {
                        practitionerProfile.put("practitionerPhone", practitioner.getPhonenumber());
                    }
                }
            }
            catch (Exception ignored) {}
        }

        JSONObject configuredPractitionerProfile = parsePayload(getClinicSetting("practitionerProfile"));
        mergeProfileFallback(practitionerProfile, configuredPractitionerProfile, "practitionerName");
        mergeProfileFallback(practitionerProfile, configuredPractitionerProfile, "title");
        mergeProfileFallback(practitionerProfile, configuredPractitionerProfile, "organization");
        mergeProfileFallback(practitionerProfile, configuredPractitionerProfile, "organizationNumber");
        mergeProfileFallback(practitionerProfile, configuredPractitionerProfile, "practitionerEmail");
        mergeProfileFallback(practitionerProfile, configuredPractitionerProfile, "practitionerPhone");
        mergeProfileFallback(practitionerProfile, "practitionerName", getClinicSetting("practitionerName"));
        mergeProfileFallback(practitionerProfile, "organization", getClinicSetting("practitionerOrganization"));
        mergeProfileFallback(practitionerProfile, "organizationNumber", getClinicSetting("practitionerOrganizationNumber"));

        if (StringUtils.isBlank(practitionerProfile.getString("organization"))
                && StringUtils.isNotBlank(practitionerProfile.getString("regulatoryBody")))
        {
            practitionerProfile.put("organization", practitionerProfile.getString("regulatoryBody"));
        }
        if (StringUtils.isBlank(practitionerProfile.getString("organizationNumber"))
                && StringUtils.isNotBlank(practitionerProfile.getString("registrationNumber")))
        {
            practitionerProfile.put("organizationNumber", practitionerProfile.getString("registrationNumber"));
        }
        return practitionerProfile;
    }

    private void mergeProfileFallback(JSONObject target, JSONObject fallback, String key)
    {
        if (target == null || fallback == null || StringUtils.isBlank(key))
        {
            return;
        }
        mergeProfileFallback(target, key, fallback.getString(key));
    }

    private void mergeProfileFallback(JSONObject target, String key, String value)
    {
        if (target == null || StringUtils.isBlank(key))
        {
            return;
        }
        if (StringUtils.isBlank(target.getString(key)) && hasMeaningfulValue(value))
        {
            target.put(key, value.trim());
        }
    }

    private JSONObject parsePayload(String payloadStr)
    {
        if (payloadStr != null && !payloadStr.isEmpty())
        {
            try
            {
                return JSON.parseObject(payloadStr);
            }
            catch (Exception ignored)
            {
                // fall through
            }
        }
        return new JSONObject();
    }

    private String safeStr(JSONObject obj, String key)
    {
        return safeValue(obj.getString(key));
    }

    private String safeValue(String value)
    {
        String text = cleanText(value);
        return text != null ? text : "-";
    }

    private String safeContent(String content)
    {
        String text = cleanText(content);
        return text != null ? text : "-";
    }

    private String cleanText(String value)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() || "-".equals(text) || "[]".equals(text) || "{}".equals(text) ? null : text;
    }

    private String formatHerbDisplayName(JSONObject herb)
    {
        if (herb == null)
        {
            return "-";
        }
        Object nameValue = firstMeaningful(herb.get("herbName"), herb.get("name"));
        String name = nameValue != null ? cleanText(String.valueOf(nameValue)) : null;
        String pinyin = cleanText(herb.getString("pinyin"));
        String latinName = cleanText(firstNonBlankOrNull(herb.getString("latinName"), herb.getString("latin")));
        TcmHerbDict dict = resolveHerbDict(herb.getString("herbDictId"));
        if (dict != null)
        {
            if (name == null)
            {
                name = cleanText(dict.getName());
            }
            if (pinyin == null)
            {
                pinyin = cleanText(dict.getPinyin());
            }
            if (latinName == null)
            {
                latinName = cleanText(dict.getLatinName());
            }
        }
        List<String> parts = new ArrayList<>();
        addUniqueText(parts, name);
        addUniqueText(parts, pinyin);
        addUniqueText(parts, latinName);
        return parts.isEmpty() ? "-" : String.join(" ", parts);
    }

    private TcmHerbDict resolveHerbDict(String herbDictId)
    {
        String id = cleanText(herbDictId);
        if (id == null || herbDictMapper == null)
        {
            return null;
        }
        try
        {
            return herbDictMapper.selectTcmHerbDictById(id);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private void addUniqueText(List<String> parts, String value)
    {
        String text = cleanText(value);
        if (text == null)
        {
            return;
        }
        for (String existing : parts)
        {
            if (existing.equalsIgnoreCase(text))
            {
                return;
            }
        }
        parts.add(text);
    }

    private String firstNonBlankOrNull(String... values)
    {
        for (String value : values)
        {
            String text = cleanText(value);
            if (text != null)
            {
                return text;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values)
    {
        if (values == null)
        {
            return "-";
        }
        for (String value : values)
        {
            if (hasMeaningfulValue(value))
            {
                return value.trim();
            }
        }
        return "-";
    }

    private String getClinicName()
    {
        try
        {
            TcmClinicSetting setting = settingMapper.selectSettingByKey("clinicName");
            return normalizeClinicName(setting != null ? setting.getSettingValue() : null);
        }
        catch (Exception e)
        {
            return DEFAULT_CLINIC_NAME;
        }
    }

    private String normalizeClinicName(String value)
    {
        String text = cleanText(value);
        if (text == null
                || "TCM Clinic".equalsIgnoreCase(text)
                || "TCM Clinic Management System".equalsIgnoreCase(text)
                || "\u8bca\u6240".equals(text))
        {
            return DEFAULT_CLINIC_NAME;
        }
        return text;
    }

    private String normalizeGender(String value)
    {
        String text = cleanText(value);
        if (text == null)
        {
            return "-";
        }
        String normalized = text.toLowerCase();
        if ("male".equals(normalized) || "m".equals(normalized) || "man".equals(normalized)
                || "boy".equals(normalized) || "\u7537".equals(text) || "\u7537\u6027".equals(text))
        {
            return "Male";
        }
        if ("female".equals(normalized) || "f".equals(normalized) || "woman".equals(normalized)
                || "girl".equals(normalized) || "\u5973".equals(text) || "\u5973\u6027".equals(text))
        {
            return "Female";
        }
        if ("prefer not to say".equals(normalized) || "prefer-not-to-say".equals(normalized)
                || "prefer not say".equals(normalized) || "unknown".equals(normalized)
                || "undisclosed".equals(normalized) || "\u4e0d\u60f3\u8bf4".equals(text)
                || "\u4e0d\u613f\u900f\u9732".equals(text))
        {
            return "Prefer not to say";
        }
        return text;
    }

    private String getClinicSetting(String key)
    {
        try
        {
            TcmClinicSetting setting = settingMapper.selectSettingByKey(key);
            return setting != null ? setting.getSettingValue() : "";
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private void updatePatientConsentMeta(TcmPatient patient, Map<String, String> result)
    {
        JSONObject payload = parsePayload(patient.getPayload());
        payload.put("consentPdfPath", result.get("filePath"));
        payload.put("consentPdfUrl", result.get("url"));
        payload.put("consentPdfGeneratedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        patient.setPayload(payload.toJSONString());
        patientMapper.updateTcmPatient(patient);
    }

    private void updateConsultationPdfMeta(TcmConsultation consultation, Map<String, String> result, String type)
    {
        // Rendering and cloud backup can overlap a payment or clinical edit.
        // Merge only PDF metadata into the current payload with an atomic compare-and-set.
        for (int attempt = 0; attempt < 3; attempt++)
        {
            TcmConsultation current = consultationMapper.selectTcmConsultationById(consultation.getId());
            if (current == null)
            {
                throw new ServiceException("consultation not found");
            }
            String expectedPayload = current.getPayload();
            JSONObject payload = parsePayload(expectedPayload);
            if ("report".equals(type))
            {
                payload.put("reportPdfPath", result.get("filePath"));
                payload.put("reportPdfUrl", result.get("url"));
                payload.put("consultationPdfPath", result.get("filePath"));
                payload.put("consultationPdfUrl", result.get("url"));
                payload.put("reportPdfGeneratedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            }
            else if ("invoice".equals(type))
            {
                payload.put("invoicePdfPath", result.get("filePath"));
                payload.put("invoicePdfUrl", result.get("url"));
                payload.put("invoicePdfGeneratedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            }
            TcmConsultation update = new TcmConsultation();
            update.setId(consultation.getId());
            update.setPayload(payload.toJSONString());
            if (consultationMapper.updatePdfPayloadIfUnchanged(update, expectedPayload) == 1)
            {
                return;
            }
        }
        throw new ServiceException("Consultation changed while saving PDF; please retry");
    }

    private void insertConsentFileRecord(TcmPatient patient, String resourcePath)
    {
        TcmPatientFile file = new TcmPatientFile();
        file.setPatientId(patient.getId());
        file.setFileType("consent_pdf");
        file.setFileName("consent-" + safeValue(patient.getName()) + ".pdf");
        file.setFilePath(resourcePath);
        patientFileService.insertTcmPatientFile(file);
    }

    private void insertConsultationFileRecord(TcmConsultation consultation, String fileType, String prefix, String resourcePath)
    {
        if (patientFileService.selectTcmPatientFileByPath(resourcePath) != null)
        {
            return;
        }
        TcmPatientFile file = new TcmPatientFile();
        file.setPatientId(consultation.getPatientId());
        file.setConsultationId(consultation.getId());
        file.setFileType(fileType);
        file.setFileName(prefix + "-"
                + safeFileToken(resolveConsultationFileDate(consultation), "date")
                + "-" + resolveConsultationFileVersion(consultation)
                + ".pdf");
        file.setFilePath(resourcePath);
        patientFileService.insertTcmPatientFile(file);
    }

    private String resolveConsultationFileDate(TcmConsultation consultation)
    {
        String date = consultation != null ? cleanText(consultation.getConsultDate()) : null;
        if (date != null && date.length() >= 10)
        {
            return date.substring(0, 10);
        }
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    }

    private String resolveConsultationFileVersion(TcmConsultation consultation)
    {
        int version = consultation != null && consultation.getVersion() != null && consultation.getVersion() > 0
                ? consultation.getVersion()
                : 1;
        return "v" + version;
    }

    private String safeFileToken(String value, String fallback)
    {
        String text = cleanText(value);
        if (text == null)
        {
            text = fallback;
        }
        text = text.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return text.isEmpty() ? fallback : text;
    }

    private void ensureDir(String filePath)
    {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists())
        {
            parent.mkdirs();
        }
    }

    private static class SignatureBlock
    {
        private final String label;
        private final String imageRef;
        private final float maxHeight;
        private final String warningPrefix;

        SignatureBlock(String label, String imageRef, float maxHeight, String warningPrefix)
        {
            this.label = label;
            this.imageRef = imageRef;
            this.maxHeight = maxHeight;
            this.warningPrefix = warningPrefix;
        }
    }
}
