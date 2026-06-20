package com.liverpool.ms_home.domain.port.inbound;

import com.liverpool.ms_home.domain.model.home.HomePage;
import com.liverpool.ms_home.domain.model.home.HomePageQuery;

/**
 * Inbound port: compose the Home page layout (ordering + static resolution + dynamic placeholders).
 * This is the only entry point the REST adapter depends on (dependencies point inward, Rule 1).
 */
public interface GetHomePageUseCase {

    /**
     * Composes the Home page for the given query.
     *
     * @param query locale, path, preview flag and session context
     * @return the ordered, composed Home page
     */
    HomePage getHomePage(HomePageQuery query);
}
