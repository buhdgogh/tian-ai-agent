package com.tianaiagent.domain.medical.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MedicalSafetyServiceTest {
    private final MedicalSafetyService safety = new MedicalSafetyService();

    @Test
    void escalatesEmergencySignalsWithoutCallingModel() {
        var result = safety.assess("突然胸痛并且呼吸困难怎么办");
        assertThat(result.emergency()).isTrue();
        assertThat(result.level()).isEqualTo("EMERGENCY");
        assertThat(result.message()).contains("120", "急诊");
    }

    @Test
    void marksMedicationQuestionsAsHighRisk() {
        var result = safety.assess("这个处方药的剂量能不能调整");
        assertThat(result.emergency()).isFalse();
        assertThat(result.level()).isEqualTo("HIGH");
    }
}
