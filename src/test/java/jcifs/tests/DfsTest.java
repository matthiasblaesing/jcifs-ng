/*
 * © 2017 AgNO3 Gmbh & Co. KG
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package jcifs.tests;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jcifs.Address;
import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.DfsReferralData;
import jcifs.DfsResolver;
import jcifs.SmbResource;
import jcifs.SmbTransport;


/**
 * @author mbechler
 *
 */
@RunWith ( Parameterized.class )
@SuppressWarnings ( "javadoc" )
public class DfsTest extends BaseCIFSTest {

    private static final Logger log = LoggerFactory.getLogger(DfsTest.class);


    /**
     * @param name
     * @param properties
     */
    public DfsTest ( String name, Map<String, String> properties ) {
        super(name, properties);
    }


    @Parameters ( name = "{0}" )
    public static Collection<Object> configs () {
        return getConfigs("smb2");
    }


    @Test
    public void resolveDC () throws CIFSException {
        CIFSContext context = withAnonymousCredentials();
        DfsResolver dfs = context.getDfs();

        try ( SmbTransport dc = dfs.getDc(context, getTestDomain()) ) {
            Address addr = dc.getRemoteAddress();
            String remoteHostName = dc.getRemoteHostName();
            assertNotNull(addr);
            assertNotNull(remoteHostName);
            assertEquals(getTestServer(), remoteHostName);
        }
    }


    @Test
    public void resolveDomains () throws CIFSException {
        CIFSContext context = getContext();
        context = withTestNTLMCredentials(context);
        DfsResolver dfs = context.getDfs();

        String testDomain = getTestDomain();
        assertTrue(dfs.isTrustedDomain(context, testDomain));
        String shortDom = getProperties().get(TestProperties.TEST_DOMAIN_SHORT);
        if ( shortDom != null ) {
            assertTrue(dfs.isTrustedDomain(context, shortDom));
        }
    }


    @Test
    public void resolveRoot () throws CIFSException, URISyntaxException {
        DfsReferralData ref = doResolve(null, "", true);

        assertNotNull(ref);
        assertEquals(getTestServer().toLowerCase(Locale.ROOT), ref.getServer().toLowerCase(Locale.ROOT));

    }


    @Test
    public void testStandalone () throws CIFSException, URISyntaxException {
        URI uri = new URI(getTestShareURL());
        Assume.assumeTrue("Is domain DFS", uri.getHost().equals(getTestServer()));

        CIFSContext context = getContext();
        context = withTestNTLMCredentials(context);

        try ( SmbResource root = context.get(getTestShareURL()) ) {
            root.exists();

            try ( SmbResource t = root.resolve(makeRandomName()) ) {
                try {
                    t.createNewFile();
                }
                finally {
                    t.delete();
                }
            }
        }

        String dfsTestSharePath = getDFSTestSharePath();
        DfsReferralData ref = doResolve(dfsTestSharePath, "", false);
        assertNotNull(ref);
        assertEquals(getTestServer().toLowerCase(Locale.ROOT), ref.getServer().toLowerCase(Locale.ROOT));
    }


    @Test
    public void resolveShare () throws CIFSException, URISyntaxException {
        String dfsTestSharePath = getDFSTestSharePath();
        DfsReferralData ref = doResolve(dfsTestSharePath, "", true);

        assertNotNull(ref);
        assertEquals(getTestServer().toLowerCase(Locale.ROOT), ref.getServer().toLowerCase(Locale.ROOT));
        assertEquals(dfsTestSharePath.length() - 1, ref.getPathConsumed());
    }


    @Test
    public void resolveRootNotExist () throws CIFSException, URISyntaxException {
        DfsReferralData ref = doResolve("\\doesnotexist\\", null, false);
        assertNull(ref);

        ref = doResolve("\\deep\\doesnotexist\\", null, false);
        assertNull(ref);
    }


    @Test
    public void resolveNonDfs () throws CIFSException {
        CIFSContext context = getContext();
        context = withTestNTLMCredentials(context);
        DfsResolver dfs = context.getDfs();
        DfsReferralData ref = dfs.resolve(context, getTestServer(), getTestShare(), "");
        assertNull(ref);
    }


    @Test
    public void resolveCacheMatch () throws CIFSException, URISyntaxException {
        DfsReferralData ref = doResolve(null, "", true);
        DfsReferralData ref2 = doResolve(null, "", true);
        DfsReferralData ref3 = doResolve(null, "foo", true);
        assertNotNull(ref);
        assertNotNull(ref2);
        assertNotNull(ref3);
        assertEquals(ref, ref2);
        assertEquals(ref, ref3);
    }


    @Test
    public void resolveCacheNonMatch () throws CIFSException, URISyntaxException {
        String dfsTestSharePath = getDFSTestSharePath();
        DfsReferralData ref = doResolve("", "", true);
        DfsReferralData ref2 = doResolve(dfsTestSharePath, "", true);
        assertNotNull(ref);
        assertNotNull(ref2);
        assertNotEquals(ref, ref2);
    }


    @Test
    public void resolveCacheNonMatch2 () throws CIFSException, URISyntaxException {
        String dfsTestSharePath = getDFSTestSharePath();
        DfsReferralData ref = doResolve(dfsTestSharePath, "", true);
        DfsReferralData ref2 = doResolve("", "", true);
        assertNotNull(ref);
        assertNotNull(ref2);
        assertNotEquals(ref, ref2);
    }


    /**
     * @return
     * @throws URISyntaxException
     * @throws CIFSException
     */
    private DfsReferralData doResolve ( String link, String relative, boolean domain ) throws URISyntaxException, CIFSException {
        CIFSContext context = getContext();
        context = withTestNTLMCredentials(context);
        DfsResolver dfs = context.getDfs();

        String dfsShare = getDFSShare();
        String dfsSharePath = getDFSTestSharePath();

        String target = domain ? getTestDomain() : getTestServer();

        String path = link != null ? link : dfsSharePath + relative;
        log.info("Resolving \\" + target + "\\" + dfsShare + path);
        DfsReferralData ref = dfs.resolve(context, target, dfsShare, path);

        if ( ref != null ) {
            do {
                log.info("ref " + ref);
            }
            while ( ( ref.next() != ref ) && ( ref = ref.next() ) != null );
        }

        return ref;
    }


    /**
     * @param dfsShare
     * @return
     * @throws URISyntaxException
     */
    private String getDFSTestSharePath () throws URISyntaxException {
        String dfsShare = getDFSShare();
        URI dfsShareRoot = new URI(getTestShareURL());
        return '\\' + dfsShareRoot.getPath().substring(2 + dfsShare.length()).replace('/', '\\');
    }


    /**
     * @return
     * @throws URISyntaxException
     */
    private String getDFSShare () throws URISyntaxException {
        URI dfsRoot = new URI(getDFSRootURL());
        String dfsRootPath = dfsRoot.getPath();
        int firstSep = dfsRootPath.indexOf('/', 1);
        String dfsShare;
        if ( firstSep > 0 ) {
            dfsShare = dfsRootPath.substring(1, firstSep);
        }
        else {
            dfsShare = dfsRootPath.substring(1, dfsRootPath.length() - 1);
        }
        return dfsShare;
    }

}
