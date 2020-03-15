/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.kudu;

import com.facebook.presto.spi.ConnectorInsertTableHandle;
import com.facebook.presto.spi.ConnectorOutputTableHandle;
import com.facebook.presto.spi.ConnectorPageSink;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PageSinkProperties;
import com.facebook.presto.spi.connector.ConnectorPageSinkProvider;
import com.facebook.presto.spi.connector.ConnectorTransactionHandle;

import javax.inject.Inject;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public class KuduPageSinkProvider
        implements ConnectorPageSinkProvider
{
    private final KuduClientSession clientSession;

    @Inject
    public KuduPageSinkProvider(KuduClientSession clientSession)
    {
        this.clientSession = requireNonNull(clientSession, "clientSession is null");
    }

    @Override
    public ConnectorPageSink createPageSink(ConnectorTransactionHandle transactionHandle, ConnectorSession session, ConnectorOutputTableHandle outputTableHandle, PageSinkProperties pageSinkProperties)
    {
        checkArgument(!pageSinkProperties.isPartitionCommitRequired(), "Kudu connector does not support partition commit");
        requireNonNull(outputTableHandle, "outputTableHandle is null");
        checkArgument(outputTableHandle instanceof KuduOutputTableHandle, "outputTableHandle is not an instance of KuduOutputTableHandle");
        KuduOutputTableHandle handle = (KuduOutputTableHandle) outputTableHandle;

        return new KuduPageSink(session, clientSession, handle);
    }

    @Override
    public ConnectorPageSink createPageSink(ConnectorTransactionHandle transactionHandle, ConnectorSession session, ConnectorInsertTableHandle insertTableHandle, PageSinkProperties pageSinkProperties)
    {
        checkArgument(!pageSinkProperties.isPartitionCommitRequired(), "Kudu connector does not support partition commit");
        requireNonNull(insertTableHandle, "insertTableHandle is null");
        checkArgument(insertTableHandle instanceof KuduInsertTableHandle, "insertTableHandle is not an instance of KuduInsertTableHandle");
        KuduInsertTableHandle handle = (KuduInsertTableHandle) insertTableHandle;

        return new KuduPageSink(session, clientSession, handle);
    }
}
