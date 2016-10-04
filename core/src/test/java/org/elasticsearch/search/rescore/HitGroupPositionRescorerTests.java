/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.rescore;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.test.ESIntegTestCase;

import org.junit.Test;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.*;


public class HitGroupPositionRescorerTests extends ESIntegTestCase {
    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(PositionDeclineScriptPlugin.class);
    }

    @Test
    public void testEmptyIndex() throws IOException {
        assertAcked(prepareCreate("test")
                    .addMapping("product",
                                jsonBuilder()
                                .startObject().startObject("product").startObject("properties")
                                .startObject("company_id").field("type", "integer").endObject()
                                .endObject().endObject().endObject())
                    .setSettings(Settings.settingsBuilder()
                                 .put(indexSettings())
                                 .put("index.number_of_shards", 1)));
        ensureYellow();
        refresh();

        SearchResponse searchResponse;
        MatchAllQueryBuilder queryBuilder = QueryBuilders.matchAllQuery();

        searchResponse = client().prepareSearch()
            .setQuery(queryBuilder)
                .setRescorer(RescoreBuilder.hitGroupPositionRescorer("company_id", new Script("position_decline_script", ScriptService.ScriptType.INLINE, "native", null)))
            .setRescoreWindow(5)
            .execute()
            .actionGet();
        assertHitCount(searchResponse, 0);
    }
    
    @Test
    public void test() throws IOException {
        assertAcked(prepareCreate("test")
            .addMapping("product",
                jsonBuilder()
                .startObject()
                    .startObject("product")
                        .startObject("properties")
                            .startObject("name")
                                .field("type", "string")
                                .field("analyzer", "whitespace")
                            .endObject()
                            .startObject("company_id")
                                .field("type", "integer")
                            .endObject()
                        .endObject()
                    .endObject()
                .endObject())
            .setSettings(Settings.settingsBuilder()
                .put(indexSettings())
                .put("index.number_of_shards", 1)));

        client().prepareIndex("test", "product", "1")
            .setSource("name", "the quick brown fox",
                "company_id", 1)
            .execute()
            .actionGet();
        client().prepareIndex("test", "product", "2")
            .setSource("name", "the quick lazy huge fox jumps over the tree",
                "company_id", 2)
            .execute()
            .actionGet();
        client().prepareIndex("test", "product", "3")
            .setSource("name", "quick huge brown fox",
                "company_id", 1)
            .execute()
            .actionGet();
        client().prepareIndex("test", "product", "4")
            .setSource("name", "the quick lonely fox")
            .execute()
            .actionGet();
        ensureYellow();
        refresh();

        SearchResponse searchResponse;
        MatchQueryBuilder queryBuilder = QueryBuilders
            .matchQuery("name", "the quick brown")
            .operator(MatchQueryBuilder.Operator.OR);

        // No rescore
        searchResponse = client().prepareSearch()
            .setQuery(queryBuilder)
            .execute()
            .actionGet();
        assertHitCount(searchResponse, 4);
        assertOrderedSearchHits(searchResponse, "1", "3", "4", "2");

        // With rescoring
        searchResponse = client().prepareSearch()
            .setQuery(queryBuilder)
            .setRescorer(RescoreBuilder.hitGroupPositionRescorer("company_id",
                new Script("position_decline_script",
                    ScriptService.ScriptType.INLINE, "native", null)))
            .setRescoreWindow(5)
            .execute()
            .actionGet();
        assertHitCount(searchResponse, 4);
        assertOrderedSearchHits(searchResponse, "1", "4", "2", "3");

        // Rescoring with script params
        searchResponse = client().prepareSearch()
            .setQuery(queryBuilder)
            .setRescorer(RescoreBuilder.hitGroupPositionRescorer("company_id",
                new Script("position_decline_script",
                    ScriptService.ScriptType.INLINE,
                    "native",
                    (Map<String,Float>) ImmutableMap.of("m", 2.0f, "a", 1.0f, "b", 2.0f))))
            .setRescoreWindow(5)
            .execute()
            .actionGet();
        assertHitCount(searchResponse, 4);
        assertOrderedSearchHits(searchResponse, "1", "4", "3", "2");

        // Small size
        searchResponse = client().prepareSearch()
            .setQuery(queryBuilder)
            .setSize(2)
            .setRescorer(RescoreBuilder.hitGroupPositionRescorer("company_id",
                new Script("position_decline_script",
                    ScriptService.ScriptType.INLINE,
                    "native",
                    null)))
            .setRescoreWindow(5)
            .execute()
            .actionGet();
        assertHitCount(searchResponse, 4);
        assertOrderedSearchHits(searchResponse, "1", "4");

        // Small rescoring window
        searchResponse = client().prepareSearch()
            .setQuery(queryBuilder)
            .setRescorer(RescoreBuilder.hitGroupPositionRescorer("company_id",
                new Script("position_decline_script",
                    ScriptService.ScriptType.INLINE,
                    "native",
                    null)))
            .setRescoreWindow(3)
            .execute()
            .actionGet();
        assertHitCount(searchResponse, 4);
        assertOrderedSearchHits(searchResponse, "1", "4", "3", "2");
    }
}
