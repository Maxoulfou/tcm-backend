package com.ruoyi.hospital.controller;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.domain.TcmAppointment;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.service.ITcmAppointmentService;
import com.ruoyi.hospital.service.ITcmAppointmentNotificationService;
import com.ruoyi.hospital.service.ITcmPatientService;
import com.ruoyi.hospital.service.ITcmSettingsService;

/**
 * 问诊表单公开接口（无需登录）
 */
@Anonymous
@RestController
@RequestMapping("/api/intake")
public class TcmIntakeController
{
    private static final String DEFAULT_CLINIC_NAME = "OTCM Acupuncture Clinic";

    @Autowired
    private ITcmAppointmentService appointmentService;

    @Autowired
    private ITcmPatientService patientService;

    @Autowired
    private ITcmAppointmentNotificationService appointmentNotificationService;

    @Autowired
    private ITcmSettingsService settingsService;

    /**
     * 根据令牌获取问诊表单信息（公开接口）
     */
    @GetMapping("/{token}")
    public Map<String, Object> getIntakeInfo(@PathVariable String token)
    {
        Map<String, Object> settings = settingsService.getBundle();
        TcmAppointment appt = appointmentService.selectTcmAppointmentByIntakeToken(token);
        if (appt != null)
        {
            TcmPatient patient = patientService.selectTcmPatientById(appt.getPatientId());

            Map<String, Object> result = new HashMap<>();
            result.put("scope", "appointment");
            result.put("appointmentId", appt.getId());
            result.put("patientName", patient != null ? patient.getName() : "");
            putPatientInfo(result, patient);
            result.put("serviceType", appt.getServiceType());
            result.put("startTime", appt.getStartTime());
            result.put("intakeSubmitted", appt.getIntakeSubmitted() != null && appt.getIntakeSubmitted() == 1);
            result.put("clinicName", normalizeClinicName(settings.get("clinicName")));
            result.put("clinicAddress", settings.getOrDefault("clinicAddress", ""));
            result.put("clinicPhone", settings.getOrDefault("clinicPhone", ""));
            return result;
        }

        TcmPatient patient = patientService.selectByIntakeToken(token);

        Map<String, Object> result = new HashMap<>();
        result.put("scope", "patient");
        result.put("patientId", patient.getId());
        result.put("patientName", patient.getName() != null ? patient.getName() : "");
        putPatientInfo(result, patient);
        result.put("serviceType", null);
        result.put("startTime", null);
        result.put("intakeSubmitted", false);
        result.put("clinicName", normalizeClinicName(settings.get("clinicName")));
        result.put("clinicAddress", settings.getOrDefault("clinicAddress", ""));
        result.put("clinicPhone", settings.getOrDefault("clinicPhone", ""));
        return result;
    }

    /**
     * 提交问诊表单（公开接口）
     */
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/{token}/submit")
    public Map<String, Object> submitIntakeForm(@PathVariable String token,
            @RequestBody Map<String, Object> formData)
    {
        validateIntakeForm(formData);

        TcmAppointment appt = appointmentService.selectTcmAppointmentByIntakeToken(token);
        if (appt != null)
        {
            if (appt.getIntakeSubmitted() != null && appt.getIntakeSubmitted() == 1)
            {
                throw new ServiceException("该表单已提交");
            }

            TcmAppointment submitted = appointmentService.completeIntake(appt.getId(), token, formData);
            patientService.saveLatestIntakeForm(submitted.getPatientId(), formData);

            Map<String, Object> result = new HashMap<>();
            result.put("ok", true);
            result.put("scope", "appointment");
            return result;
        }

        TcmPatient patient = patientService.saveIntakeFormByToken(token, formData);

        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("scope", "patient");
        result.put("patientId", patient.getId());
        return result;
    }

    private void validateIntakeForm(Map<String, Object> formData)
    {
        Object chiefComplaint = formData != null ? formData.get("chiefComplaint") : null;
        if (chiefComplaint == null || String.valueOf(chiefComplaint).trim().isEmpty())
        {
            throw new ServiceException("Please fill in the chief complaint first");
        }
    }

    private void putPatientInfo(Map<String, Object> result, TcmPatient patient)
    {
        if (result == null || patient == null)
        {
            return;
        }
        JSONObject payload;
        try
        {
            payload = patient.getPayload() != null && !patient.getPayload().trim().isEmpty()
                    ? JSON.parseObject(patient.getPayload())
                    : new JSONObject();
        }
        catch (Exception e)
        {
            payload = new JSONObject();
        }
        result.put("firstName", defaultText(patient.getFirstName(), payload.getString("firstName")));
        result.put("lastName", defaultText(patient.getLastName(), payload.getString("lastName")));
        result.put("gender", normalizeGender(defaultText(payload.getString("gender"), "")));
        result.put("dateOfBirth", defaultText(payload.getString("dateOfBirth"), ""));
        result.put("email", defaultText(patient.getEmail(), payload.getString("email")));
        result.put("phone", defaultText(patient.getPhone(), payload.getString("phone")));
        result.put("addressStreet", defaultText(payload.getString("addressStreet"), payload.getString("address")));
        result.put("addressCity", defaultText(payload.getString("addressCity"), ""));
        result.put("addressState", defaultText(payload.getString("addressState"), ""));
        result.put("addressCountry", defaultText(payload.getString("addressCountry"), "CA"));
        result.put("addressPostal", defaultText(payload.getString("addressPostal"), ""));
    }

    private String defaultText(String primary, String fallback)
    {
        String value = primary != null ? primary.trim() : "";
        if (!value.isEmpty())
        {
            return value;
        }
        return fallback != null ? fallback.trim() : "";
    }

    private String normalizeClinicName(Object value)
    {
        String text = value != null ? String.valueOf(value).trim() : "";
        if (text.isEmpty()
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
        String text = value != null ? value.trim() : "";
        if (text.isEmpty())
        {
            return "";
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

    @PostMapping("/{token}/cancel")
    public Map<String, Object> cancelAppointment(@PathVariable String token)
    {
        TcmAppointment appointment = appointmentNotificationService.cancelByIntakeToken(token, "patient_intake_form");
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("appointmentId", appointment.getId());
        result.put("status", appointment.getStatus());
        return result;
    }
}
