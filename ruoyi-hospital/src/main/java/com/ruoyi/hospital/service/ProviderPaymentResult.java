package com.ruoyi.hospital.service;

import com.ruoyi.hospital.domain.TcmConsultation;

/** Internal result decided while holding the consultation payment lock. */
public final class ProviderPaymentResult
{
    private final TcmConsultation consultation;
    private final boolean newlyRecorded;

    public ProviderPaymentResult(TcmConsultation consultation, boolean newlyRecorded)
    {
        this.consultation = consultation;
        this.newlyRecorded = newlyRecorded;
    }

    public TcmConsultation getConsultation()
    {
        return consultation;
    }

    public boolean isNewlyRecorded()
    {
        return newlyRecorded;
    }
}
