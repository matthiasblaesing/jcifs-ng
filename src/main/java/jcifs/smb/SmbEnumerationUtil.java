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
package jcifs.smb;


import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jcifs.Address;
import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.CloseableIterator;
import jcifs.ResourceFilter;
import jcifs.ResourceNameFilter;
import jcifs.SmbConstants;
import jcifs.SmbResource;
import jcifs.SmbResourceLocator;
import jcifs.dcerpc.DcerpcHandle;
import jcifs.dcerpc.msrpc.MsrpcDfsRootEnum;
import jcifs.dcerpc.msrpc.MsrpcShareEnum;
import jcifs.internal.smb1.net.NetShareEnum;
import jcifs.internal.smb1.net.NetShareEnumResponse;
import jcifs.internal.smb1.trans.SmbComTransaction;
import jcifs.internal.smb1.trans.SmbComTransactionResponse;


/**
 * @author mbechler
 *
 */
final class SmbEnumerationUtil {

    private static final Logger log = LoggerFactory.getLogger(SmbEnumerationUtil.class);


    /**
     * 
     */
    private SmbEnumerationUtil () {}


    static FileEntry[] doDfsRootEnum ( CIFSContext ctx, SmbResourceLocator loc ) throws IOException {
        MsrpcDfsRootEnum rpc;
        try ( DcerpcHandle handle = DcerpcHandle.getHandle("ncacn_np:" + loc.getAddress().getHostAddress() + "[\\PIPE\\netdfs]", ctx) ) {
            rpc = new MsrpcDfsRootEnum(loc.getServer());
            handle.sendrecv(rpc);
            if ( rpc.retval != 0 )
                throw new SmbException(rpc.retval, true);
            return rpc.getEntries();
        }
    }


    static FileEntry[] doMsrpcShareEnum ( CIFSContext ctx, String host, Address address ) throws IOException {
        MsrpcShareEnum rpc = new MsrpcShareEnum(host);
        /*
         * JCIFS will build a composite list of shares if the target host has
         * multiple IP addresses such as when domain-based DFS is in play. Because
         * of this, to ensure that we query each IP individually without re-resolving
         * the hostname and getting a different IP, we must use the current addresses
         * IP rather than just url.getHost() like we were using prior to 1.2.16.
         */
        try ( DcerpcHandle handle = DcerpcHandle.getHandle("ncacn_np:" + address.getHostAddress() + "[\\PIPE\\srvsvc]", ctx) ) {
            handle.sendrecv(rpc);
            if ( rpc.retval != 0 )
                throw new SmbException(rpc.retval, true);
            return rpc.getEntries();
        }
    }


    static FileEntry[] doNetShareEnum ( SmbTreeHandleImpl th ) throws CIFSException {
        SmbComTransaction req = new NetShareEnum(th.getConfig());
        SmbComTransactionResponse resp = new NetShareEnumResponse(th.getConfig());
        th.send(req, resp);
        if ( resp.getStatus() != WinError.ERROR_SUCCESS )
            throw new SmbException(resp.getStatus(), true);

        return resp.getResults();
    }


    static CloseableIterator<SmbResource> doShareEnum ( SmbFile parent, String wildcard, int searchAttributes, ResourceNameFilter fnf,
            ResourceFilter ff ) throws CIFSException {
        // clone the locator so that the address index is not modified
        SmbResourceLocatorImpl locator = parent.fileLocator.clone();
        CIFSContext tc = parent.getContext();
        URL u = locator.getURL();

        FileEntry[] entries;

        if ( u.getPath().lastIndexOf('/') != ( u.getPath().length() - 1 ) )
            throw new SmbException(u.toString() + " directory must end with '/'");

        if ( locator.getType() != SmbConstants.TYPE_SERVER )
            throw new SmbException("The requested list operations is invalid: " + u.toString());

        Set<FileEntry> set = new HashSet<>();

        if ( tc.getDfs().isTrustedDomain(tc, locator.getServer()) ) {
            /*
             * The server name is actually the name of a trusted
             * domain. Add DFS roots to the list.
             */
            try {
                entries = doDfsRootEnum(tc, locator);
                for ( int ei = 0; ei < entries.length; ei++ ) {
                    FileEntry e = entries[ ei ];
                    if ( !set.contains(e) && ( fnf == null || fnf.accept(parent, e.getName()) ) ) {
                        set.add(e);
                    }
                }
            }
            catch ( IOException ioe ) {
                log.debug("DS enumeration failed", ioe);
            }
        }

        SmbTreeConnection treeConn = new SmbTreeConnection(tc);
        try ( SmbTreeHandleImpl th = treeConn.connectHost(locator, locator.getServerWithDfs());
              SmbSessionImpl session = th.getSession();
              SmbTransportImpl transport = session.getTransport() ) {
            try {
                entries = doMsrpcShareEnum(tc, locator.getURL().getHost(), transport.getRemoteAddress());
            }
            catch ( IOException ioe ) {
                if ( th.isSMB2() ) {
                    throw ioe;
                }
                log.debug("doMsrpcShareEnum failed", ioe);
                entries = doNetShareEnum(th);
            }
            for ( int ei = 0; ei < entries.length; ei++ ) {
                FileEntry e = entries[ ei ];
                if ( !set.contains(e) && ( fnf == null || fnf.accept(parent, e.getName()) ) ) {
                    set.add(e);
                }
            }

        }
        catch ( SmbException e ) {
            throw e;
        }
        catch ( IOException ioe ) {
            log.debug("doNetShareEnum failed", ioe);
            throw new SmbException(u.toString(), ioe);
        }
        return new ShareEnumIterator(parent, set.iterator(), ff);
    }


    @SuppressWarnings ( "resource" )
    static CloseableIterator<SmbResource> doEnum ( SmbFile parent, String wildcard, int searchAttributes, ResourceNameFilter fnf, ResourceFilter ff )
            throws CIFSException {
        if ( ff != null && ff instanceof DosFileFilter ) {
            DosFileFilter dff = (DosFileFilter) ff;
            if ( dff.wildcard != null )
                wildcard = dff.wildcard;
            searchAttributes = dff.attributes;
        }
        SmbResourceLocator locator = parent.getLocator();
        if ( locator.getURL().getHost().isEmpty() || locator.getType() == SmbConstants.TYPE_WORKGROUP ) {
            try ( SmbTreeHandleImpl th = parent.ensureTreeConnected() ) {
                return new NetServerFileEntryAdapterIterator(parent, new NetServerEnumIterator(parent, th, wildcard, searchAttributes, fnf), ff);
            }
        }
        else if ( locator.isRoot() ) {
            return doShareEnum(parent, wildcard, searchAttributes, fnf, ff);
        }

        try ( SmbTreeHandleImpl th = parent.ensureTreeConnected() ) {
            if ( th.isSMB2() ) {
                return new DirFileEntryAdapterIterator(parent, new DirFileEntryEnumIterator2(th, parent, wildcard, fnf, searchAttributes), ff);
            }
            return new DirFileEntryAdapterIterator(parent, new DirFileEntryEnumIterator1(th, parent, wildcard, fnf, searchAttributes), ff);
        }
    }


    static String[] list ( SmbFile root, String wildcard, int searchAttributes, final SmbFilenameFilter fnf, final SmbFileFilter ff )
            throws SmbException {
        try ( CloseableIterator<SmbResource> it = doEnum(root, wildcard, searchAttributes, fnf == null ? null : new ResourceNameFilter() {

            @Override
            public boolean accept ( SmbResource parent, String name ) throws CIFSException {
                if ( ! ( parent instanceof SmbFile ) ) {
                    return false;
                }
                return fnf.accept((SmbFile) parent, name);
            }
        }, ff == null ? null : new ResourceFilter() {

            @Override
            public boolean accept ( SmbResource resource ) throws CIFSException {
                if ( ! ( resource instanceof SmbFile ) ) {
                    return false;
                }
                return ff.accept((SmbFile) resource);
            }
        }) ) {

            List<String> list = new ArrayList<>();
            while ( it.hasNext() ) {
                try ( SmbResource n = it.next() ) {
                    list.add(n.getName());
                }
            }
            return list.toArray(new String[list.size()]);
        }
        catch ( CIFSException e ) {
            throw SmbException.wrap(e);
        }
    }


    static SmbFile[] listFiles ( SmbFile root, String wildcard, int searchAttributes, final SmbFilenameFilter fnf, final SmbFileFilter ff )
            throws SmbException {
        try ( CloseableIterator<SmbResource> it = doEnum(root, wildcard, searchAttributes, fnf == null ? null : new ResourceNameFilter() {

            @Override
            public boolean accept ( SmbResource parent, String name ) throws CIFSException {
                if ( ! ( parent instanceof SmbFile ) ) {
                    return false;
                }
                return fnf.accept((SmbFile) parent, name);
            }
        }, ff == null ? null : new ResourceFilter() {

            @Override
            public boolean accept ( SmbResource resource ) throws CIFSException {
                if ( ! ( resource instanceof SmbFile ) ) {
                    return false;
                }
                return ff.accept((SmbFile) resource);
            }
        }) ) {

            List<SmbFile> list = new ArrayList<>();
            while ( it.hasNext() ) {
                try ( SmbResource n = it.next() ) {
                    if ( n instanceof SmbFile ) {
                        list.add((SmbFile) n);
                    }
                }
            }
            return list.toArray(new SmbFile[list.size()]);
        }
        catch ( CIFSException e ) {
            throw SmbException.wrap(e);
        }
    }

}
