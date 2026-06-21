package com.liverpool.ms_home.adapter.inbound.rest.mapper;

import org.springframework.stereotype.Component;

import com.liverpool.ms_home.adapter.inbound.rest.dto.GlobalDataResponse;
import com.liverpool.ms_home.domain.model.globaldata.GlobalData;

/**
 * Maps the {@link GlobalData} domain model to {@link GlobalDataResponse} REST DTO.
 */
@Component
public class GlobalDataMapper {

    public GlobalDataResponse toResponse(GlobalData data) {
        return new GlobalDataResponse(
                data.locale(),
                data.featureFlags(),
                data.publicVariables(),
                data.themes(),
                data.header(),
                data.footer());
    }
}
