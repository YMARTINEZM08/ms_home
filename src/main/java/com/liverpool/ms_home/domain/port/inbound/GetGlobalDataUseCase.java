package com.liverpool.ms_home.domain.port.inbound;

import com.liverpool.ms_home.domain.model.globaldata.GlobalData;
import com.liverpool.ms_home.domain.model.globaldata.GlobalDataQuery;

/**
 * Inbound port for retrieving site-wide GlobalData from the CMS.
 *
 * <p>Implemented by {@code GetGlobalDataService} in the application layer. The REST adapter
 * calls this port; the domain never references the adapter.</p>
 */
public interface GetGlobalDataUseCase {

    /**
     * Returns the GlobalData for the given brand/locale/preview combination.
     *
     * <p>Results are served from the in-process Caffeine L1 cache when available; on a miss the
     * content-service proxy is called and the result is cached before returning.</p>
     *
     * @param query brand, locale, and preview flag derived from the incoming session context
     * @return the resolved GlobalData (never null; maps are empty when CMS fields are absent)
     */
    GlobalData getGlobalData(GlobalDataQuery query);
}
