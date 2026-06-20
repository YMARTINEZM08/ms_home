package com.liverpool.ms_home.domain.port.outbound;

import com.liverpool.ms_home.domain.model.content.ContentQuery;
import com.liverpool.ms_home.domain.model.content.HomeDefinition;

/**
 * Outbound port for retrieving the raw Home definition from the CMS (content-service proxy). The only
 * gateway through which Contentstack content enters the domain — no SDK or HTTP type leaks past it
 * (Rule 2).
 */
public interface ContentPort {

    /**
     * Fetches the raw Home definition.
     *
     * @param query brand/locale/path/preview
     * @return the ordered raw definition
     */
    HomeDefinition fetchHomeDefinition(ContentQuery query);
}
