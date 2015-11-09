package features.filters.identitybasicauth.akkatimeout

import framework.ReposeLogSearch
import framework.ReposeValveTest
import framework.category.Slow
import framework.mocks.MockIdentityService
import org.apache.commons.codec.binary.Base64
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain

import javax.servlet.http.HttpServletResponse
import javax.ws.rs.core.HttpHeaders
import org.junit.experimental.categories.Category

/**
 * Created by jennyvo on 11/9/15.
 *  using default pool with socket and http time out
 */
@Category(Slow.class)
class BasicAuthAkkatimeoutUsingDefaultConnPoolTest extends ReposeValveTest {
    def static originEndpoint
    def static identityEndpoint
    def static MockIdentityService fakeIdentityService

    def setupSpec() {
        deproxy = new Deproxy()
        reposeLogSearch.cleanLog()

        def params = properties.getDefaultTemplateParams()
        repose.configurationProvider.cleanConfigDirectory()
        repose.configurationProvider.applyConfigs("common", params);
        repose.configurationProvider.applyConfigs("features/filters/identitybasicauth", params);
        repose.configurationProvider.applyConfigs("features/filters/identitybasicauth/akkatimeout/nopool", params);

        repose.start()

        originEndpoint = deproxy.addEndpoint(properties.targetPort, 'origin service')
        fakeIdentityService = new MockIdentityService(properties.identityPort, properties.targetPort)
        identityEndpoint = deproxy.addEndpoint(properties.identityPort, 'identity service', null, fakeIdentityService.handler)
        fakeIdentityService.checkTokenValid = true
    }

    def setup() {
        fakeIdentityService.with {
            // This is required to ensure that one piece of the authentication data is changed
            // so that the cached version in the Akka Client is not used.
            client_apikey = UUID.randomUUID().toString()
            client_token = UUID.randomUUID().toString()
        }
        reposeLogSearch = new ReposeLogSearch(properties.getLogFile())
    }

    def cleanupSpec() {
        if (deproxy) {
            deproxy.shutdown()
        }

        if (repose) {
            repose.stop()
        }
    }

    def "akka timeout test, auth response time out is less than socket connection time out"() {
        given: "the HTTP Basic authentication header containing the User Name and API Key"
        def headers = [
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString((fakeIdentityService.client_username + ":" + fakeIdentityService.client_apikey).bytes)
        ]
        "Delay 19 sec"
        fakeIdentityService.with {
            sleeptime = 29000
        }

        when: "the request does have an HTTP Basic authentication header with UserName/ApiKey"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "Request should be passed from repose"
        mc.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc.handlings.size() == 1
        mc.handlings[0].request.headers.getCountByName("X-Auth-Token") == 1
        mc.handlings[0].request.headers.getFirstValue("X-Auth-Token").equals(fakeIdentityService.client_token)
    }

    def "akka timeout test, auth response time out is greater than socket connection time out"() {
        given: "the HTTP Basic authentication header containing the User Name and API Key"
        def headers = [
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString((fakeIdentityService.client_username + ":" + fakeIdentityService.client_apikey).bytes)
        ]
        "Delay 19 sec"
        fakeIdentityService.with {
            sleeptime = 32000
        }

        when: "the request does have an HTTP Basic authentication header with UserName/ApiKey"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "Request should not be passed from repose"
        mc.receivedResponse.code == "500"//HttpServletResponse.SC_GATEWAY_TIMEOUT.toString()
        mc.handlings.size() == 0
        sleep(1000)
        reposeLogSearch.searchByString("Error acquiring value from akka .* or the cache. Reason: Futures timed out after .31000 milliseconds.").size() > 0
        reposeLogSearch.searchByString("NullPointerException").size() == 0
    }
}