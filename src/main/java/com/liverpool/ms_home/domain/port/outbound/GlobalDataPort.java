package com.liverpool.ms_home.domain.port.outbound;

import com.liverpool.ms_home.domain.model.globaldata.GlobalData;
import com.liverpool.ms_home.domain.model.globaldata.GlobalDataQuery;

/**
 * Outbound port for fetching GlobalData from an external source.
 *
 * <p>Implemented by {@code GlobalDataClient} in the contentstack adapter package. The domain
 * depends only on this interface; the HTTP details live exclusively in the adapter (ADR-001).</p>
 */
public interface GlobalDataPort {

    /**
     * Fetches the raw GlobalData for the given brand/locale/preview combination.
     *
     * @param query brand, locale, and preview flag for the content-service call
     * @return the resolved GlobalData
     */
    GlobalData fetchGlobalData(GlobalDataQuery query);
}
