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
package jcifs.internal.smb2.nego;


import jcifs.CIFSContext;
import jcifs.Configuration;
import jcifs.internal.smb2.ServerMessageBlock2Request;
import jcifs.internal.smb2.Smb2Constants;
import jcifs.internal.util.SMBUtil;


/**
 * @author mbechler
 *
 */
public class Smb2NegotiateRequest extends ServerMessageBlock2Request<Smb2NegotiateResponse> {

    private int[] dialects;
    private int capabilities;
    private byte[] clientGuid = new byte[16];
    private int securityMode;
    private NegotiateContextRequest[] negotiateContexts;


    /**
     * @param config
     * @param securityMode
     */
    public Smb2NegotiateRequest ( Configuration config, int securityMode ) {
        super(config, SMB2_NEGOTIATE);
        this.securityMode = securityMode;
        if ( !config.isDfsDisabled() ) {
            this.capabilities = Smb2Constants.SMB2_GLOBAL_CAP_DFS;
        }
        this.dialects = new int[] {
            Smb2Constants.SMB2_DIALECT_0202 // , Smb2Constants.SMB2_DIALECT_0210
        };
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.internal.smb2.ServerMessageBlock2Request#createResponse(jcifs.Configuration,
     *      jcifs.internal.smb2.ServerMessageBlock2Request)
     */
    @Override
    protected Smb2NegotiateResponse createResponse ( CIFSContext tc, ServerMessageBlock2Request<Smb2NegotiateResponse> req ) {
        return new Smb2NegotiateResponse(tc.getConfig());
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.internal.CommonServerMessageBlockRequest#size()
     */
    @Override
    public int size () {
        int size = Smb2Constants.SMB2_HEADER_LENGTH + 36 + size8(2 * this.dialects.length, 4);
        if ( this.negotiateContexts != null ) {
            for ( NegotiateContextRequest ncr : this.negotiateContexts ) {
                size += size8(ncr.size());
            }
        }
        return size8(size);
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.internal.smb2.ServerMessageBlock2#writeBytesWireFormat(byte[], int)
     */
    @Override
    protected int writeBytesWireFormat ( byte[] dst, int dstIndex ) {
        int start = dstIndex;
        SMBUtil.writeInt2(36, dst, dstIndex);
        SMBUtil.writeInt2(this.dialects.length, dst, dstIndex + 2);
        dstIndex += 4;

        SMBUtil.writeInt2(this.securityMode, dst, dstIndex);
        SMBUtil.writeInt2(0, dst, dstIndex + 2); // Reserved
        dstIndex += 4;

        SMBUtil.writeInt4(this.capabilities, dst, dstIndex);
        dstIndex += 4;

        System.arraycopy(this.clientGuid, 0, dst, dstIndex, 16);
        dstIndex += 16;

        // if SMB 3.11 support negotiateContextOffset/negotiateContextCount
        int negotitateContextOffsetOffset = 0;
        if ( this.negotiateContexts == null || this.negotiateContexts.length == 0 ) {
            SMBUtil.writeInt8(0, dst, dstIndex);
        }
        else {
            negotitateContextOffsetOffset = dstIndex;
            SMBUtil.writeInt2(this.negotiateContexts.length, dst, dstIndex);
        }
        dstIndex += 8;

        for ( int dialect : this.dialects ) {
            SMBUtil.writeInt2(dialect, dst, dstIndex);
            dstIndex += 2;
        }

        dstIndex += pad8(dstIndex);

        if ( this.negotiateContexts != null && this.negotiateContexts.length != 0 ) {
            SMBUtil.writeInt4(dstIndex - getHeaderStart(), dst, negotitateContextOffsetOffset);
            for ( NegotiateContextRequest nc : this.negotiateContexts ) {
                SMBUtil.writeInt2(nc.getContextType(), dst, dstIndex);
                int lenOffset = dstIndex + 2;
                dstIndex += 4;
                dstIndex += 4; // Reserved
                int dataLen = nc.encode(dst, dstIndex);
                SMBUtil.writeInt2(dataLen, dst, lenOffset);
                dstIndex += dataLen;
                dstIndex += pad8(dstIndex);
            }
        }
        return dstIndex - start;
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.internal.smb2.ServerMessageBlock2#readBytesWireFormat(byte[], int)
     */
    @Override
    protected int readBytesWireFormat ( byte[] buffer, int bufferIndex ) {
        return 0;
    }

}
