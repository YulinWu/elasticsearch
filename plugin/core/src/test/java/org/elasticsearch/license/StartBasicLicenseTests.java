/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license;

import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.transport.Netty4Plugin;
import org.elasticsearch.xpack.core.LocalStateCompositeXPackPlugin;
import org.elasticsearch.xpack.core.XPackClientPlugin;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;

import static org.elasticsearch.test.ESIntegTestCase.Scope.SUITE;

@ESIntegTestCase.ClusterScope(scope = SUITE)
public class StartBasicLicenseTests extends AbstractLicensesIntegrationTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("node.data", true)
                .put(LicenseService.SELF_GENERATED_LICENSE_TYPE.getKey(), "basic")
                .put(NetworkModule.HTTP_ENABLED.getKey(), true).build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(LocalStateCompositeXPackPlugin.class, Netty4Plugin.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return Arrays.asList(XPackClientPlugin.class, Netty4Plugin.class);
    }

    public void testStartBasicLicense() throws Exception {
        LicensingClient licensingClient = new LicensingClient(client());
        License license = TestUtils.generateSignedLicense("trial",  License.VERSION_CURRENT, -1, TimeValue.timeValueHours(24));
        licensingClient.preparePutLicense(license).get();

        assertBusy(() -> {
            GetLicenseResponse getLicenseResponse = licensingClient.prepareGetLicense().get();
            assertEquals("trial", getLicenseResponse.license().type());
        });

        // Testing that you can start a basic license when you have no license
        if (randomBoolean()) {
            licensingClient.prepareDeleteLicense().get();
            assertNull(licensingClient.prepareGetLicense().get().license());
        }

        RestClient restClient = getRestClient();
        Response response = restClient.performRequest("GET", "/_xpack/license/basic_status");
        String body = Streams.copyToString(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
        assertEquals(200, response.getStatusLine().getStatusCode());
        assertEquals("{\"eligible_to_start_basic\":true}", body);

        Response response2 = restClient.performRequest("POST", "/_xpack/license/start_basic?acknowledge=true");
        String body2 = Streams.copyToString(new InputStreamReader(response2.getEntity().getContent(), StandardCharsets.UTF_8));
        assertEquals(200, response2.getStatusLine().getStatusCode());
        assertTrue(body2.contains("\"acknowledged\":true"));
        assertTrue(body2.contains("\"basic_was_started\":true"));

        assertBusy(() -> {
            GetLicenseResponse currentLicense = licensingClient.prepareGetLicense().get();
            assertEquals("basic", currentLicense.license().type());
        });

        long expirationMillis = licensingClient.prepareGetLicense().get().license().expiryDate();
        assertEquals(LicenseService.BASIC_SELF_GENERATED_LICENSE_EXPIRATION_MILLIS, expirationMillis);

        Response response3 = restClient.performRequest("GET", "/_xpack/license/basic_status");
        String body3 = Streams.copyToString(new InputStreamReader(response3.getEntity().getContent(), StandardCharsets.UTF_8));
        assertEquals(200, response3.getStatusLine().getStatusCode());
        assertEquals("{\"eligible_to_start_basic\":false}", body3);

        ResponseException ex = expectThrows(ResponseException.class,
                () -> restClient.performRequest("POST", "/_xpack/license/start_basic"));
        Response response4 = ex.getResponse();
        String body4 = Streams.copyToString(new InputStreamReader(response4.getEntity().getContent(), StandardCharsets.UTF_8));
        assertEquals(403, response4.getStatusLine().getStatusCode());
        assertTrue(body4.contains("\"basic_was_started\":false"));
        assertTrue(body4.contains("\"acknowledged\":true"));
        assertTrue(body4.contains("\"error_message\":\"Operation failed: Current license is basic.\""));
    }

    public void testUnacknowledgedStartBasicLicense() throws Exception {
        LicensingClient licensingClient = new LicensingClient(client());
        License license = TestUtils.generateSignedLicense("trial",  License.VERSION_CURRENT, -1, TimeValue.timeValueHours(24));
        licensingClient.preparePutLicense(license).get();

        assertBusy(() -> {
            GetLicenseResponse getLicenseResponse = licensingClient.prepareGetLicense().get();
            assertEquals("trial", getLicenseResponse.license().type());
        });

        Response response2 = getRestClient().performRequest("POST", "/_xpack/license/start_basic");
        String body2 = Streams.copyToString(new InputStreamReader(response2.getEntity().getContent(), StandardCharsets.UTF_8));
        assertEquals(200, response2.getStatusLine().getStatusCode());
        assertTrue(body2.contains("\"acknowledged\":false"));
        assertTrue(body2.contains("\"basic_was_started\":false"));
        assertTrue(body2.contains("\"error_message\":\"Operation failed: Needs acknowledgement.\""));
        assertTrue(body2.contains("\"message\":\"This license update requires acknowledgement. To acknowledge the license, " +
                "please read the following messages and call /start_basic again, this time with the \\\"acknowledge=true\\\""));
    }
}
