/*******************************************************************************
 * Copyright (c) 2011, 2017 Eurotech and/or its affiliates and others
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Eurotech - initial API and implementation
 *******************************************************************************/
package org.eclipse.kapua.service.datastore.internal.elasticsearch;

import java.util.Map;

import org.eclipse.kapua.KapuaIllegalArgumentException;
import org.eclipse.kapua.locator.KapuaLocator;
import org.eclipse.kapua.message.KapuaMessage;
import org.eclipse.kapua.message.KapuaPayload;
import org.eclipse.kapua.model.id.KapuaId;
import org.eclipse.kapua.service.datastore.ChannelInfoRegistryService;
import org.eclipse.kapua.service.datastore.ClientInfoRegistryService;
import org.eclipse.kapua.service.datastore.MetricInfoRegistryService;
import org.eclipse.kapua.service.datastore.internal.ChannelInfoRegistryFacade;
import org.eclipse.kapua.service.datastore.internal.ClientInfoRegistryFacade;
import org.eclipse.kapua.service.datastore.internal.MessageStoreFacade;
import org.eclipse.kapua.service.datastore.internal.MetricInfoRegistryFacade;
import org.eclipse.kapua.service.datastore.internal.elasticsearch.EsSchema.Metadata;
import org.eclipse.kapua.service.datastore.internal.model.ChannelInfoImpl;
import org.eclipse.kapua.service.datastore.internal.model.ClientInfoImpl;
import org.eclipse.kapua.service.datastore.internal.model.MetricInfoImpl;
import org.eclipse.kapua.service.datastore.internal.model.StorableIdImpl;
import org.eclipse.kapua.service.datastore.internal.model.query.ChannelMatchPredicateImpl;
import org.eclipse.kapua.service.datastore.internal.model.query.MessageQueryImpl;
import org.eclipse.kapua.service.datastore.internal.model.query.MetricInfoQueryImpl;
import org.eclipse.kapua.service.datastore.model.ChannelInfo;
import org.eclipse.kapua.service.datastore.model.ClientInfo;
import org.eclipse.kapua.service.datastore.model.MetricInfo;

/**
 * Datastore mediator definition
 *
 * @since 1.0.0
 */
public class DatastoreMediator implements MessageStoreMediator,
        ClientInfoRegistryMediator,
        ChannelInfoRegistryMediator,
        MetricInfoRegistryMediator {

    private static DatastoreMediator instance;

    private final EsSchema esSchema;

    private MessageStoreFacade messageStoreFacade;
    private ClientInfoRegistryFacade clientInfoStoreFacade;
    private ChannelInfoRegistryFacade channelInfoStoreFacade;
    private MetricInfoRegistryFacade metricInfoStoreFacade;

    static {
        instance = new DatastoreMediator();

        // Be sure the data registry services are instantiated
        KapuaLocator.getInstance().getService(ClientInfoRegistryService.class);
        KapuaLocator.getInstance().getService(ChannelInfoRegistryService.class);
        KapuaLocator.getInstance().getService(MetricInfoRegistryService.class);
    }

    private DatastoreMediator() {
        this.esSchema = new EsSchema();
    }

    /**
     * Get the {@link DatastoreMediator} instance (singleton)
     *
     * @return
     * @since 1.0.0
     */
    public static DatastoreMediator getInstance() {
        return instance;
    }

    /**
     * Set the message store facade
     *
     * @param messageStoreFacade
     * @since 1.0.0
     */
    public void setMessageStoreFacade(MessageStoreFacade messageStoreFacade) {
        this.messageStoreFacade = messageStoreFacade;
    }

    /**
     * Set the client info facade
     *
     * @param clientInfoStoreFacade
     * @since 1.0.0
     */
    public void setClientInfoStoreFacade(ClientInfoRegistryFacade clientInfoStoreFacade) {
        this.clientInfoStoreFacade = clientInfoStoreFacade;
    }

    /**
     * Set the channel info facade
     *
     * @param channelInfoStoreFacade
     * @since 1.0.0
     */
    public void setChannelInfoStoreFacade(ChannelInfoRegistryFacade channelInfoStoreFacade) {
        this.channelInfoStoreFacade = channelInfoStoreFacade;
    }

    /**
     * Set the metric info facade
     *
     * @param metricInfoStoreFacade
     * @since 1.0.0
     */
    public void setMetricInfoStoreFacade(MetricInfoRegistryFacade metricInfoStoreFacade) {
        this.metricInfoStoreFacade = metricInfoStoreFacade;
    }

    /*
     * Message Store Mediator methods
     */

    @Override
    public Metadata getMetadata(KapuaId scopeId, long indexedOn)
            throws EsDocumentBuilderException, EsClientUnavailableException {
        return this.esSchema.synch(scopeId, indexedOn);
    }

    @Override
    public void onUpdatedMappings(KapuaId scopeId, long indexedOn, Map<String, EsMetric> esMetrics)
            throws EsDocumentBuilderException, EsClientUnavailableException {
        this.esSchema.updateMessageMappings(scopeId, indexedOn, esMetrics);
    }

    @Override
    public void onAfterMessageStore(MessageXContentBuilder docBuilder, KapuaMessage<?, ?> message)
            throws KapuaIllegalArgumentException,
            EsDocumentBuilderException,
            EsClientUnavailableException,
            EsConfigurationException {
        ClientInfoImpl clientInfo = new ClientInfoImpl(docBuilder.getScopeId());
        clientInfo.setClientId(docBuilder.getClientId());
        clientInfo.setFirstMessageId(docBuilder.getMessageId());
        clientInfo.setFirstMessageOn(docBuilder.getTimestamp());
        String clientInfoId = ClientInfoXContentBuilder.getOrDeriveId(null, docBuilder.getScopeId(), docBuilder.getClientId());
        clientInfo.setId(new StorableIdImpl(clientInfoId));
        this.clientInfoStoreFacade.upstore(clientInfo);

        ChannelInfoImpl channelInfo = new ChannelInfoImpl(docBuilder.getScopeId());
        channelInfo.setClientId(docBuilder.getClientId());
        channelInfo.setChannel(docBuilder.getChannel());
        channelInfo.setFirstMessageId(docBuilder.getMessageId());
        channelInfo.setFirstMessageOn(docBuilder.getTimestamp());
        channelInfo.setId(new StorableIdImpl(ChannelInfoXContentBuilder.getOrDeriveId(null, channelInfo)));
        this.channelInfoStoreFacade.upstore(channelInfo);

        KapuaPayload payload = message.getPayload();
        if (payload == null)
            return;

        Map<String, Object> metrics = payload.getProperties();
        if (metrics == null)
            return;

        int i = 0;
        MetricInfoImpl[] messageMetrics = new MetricInfoImpl[metrics.size()];
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            MetricInfoImpl metricInfo = new MetricInfoImpl(docBuilder.getScopeId());
            metricInfo.setClientId(docBuilder.getClientId());
            metricInfo.setChannel(docBuilder.getChannel());
            metricInfo.setName(entry.getKey());
            metricInfo.setType(EsUtils.getEsTypeFromValue(entry.getValue()));
            metricInfo.setFirstMessageId(docBuilder.getMessageId());
            metricInfo.setFirstMessageOn(docBuilder.getTimestamp());
            metricInfo.setValue(entry.getValue());
            metricInfo.setId(new StorableIdImpl(MetricInfoXContentBuilder.getOrDeriveId(null, metricInfo)));
            messageMetrics[i++] = metricInfo;
        }

        this.metricInfoStoreFacade.upstore(messageMetrics);
    }

    /*
     * ClientInfo Store Mediator methods
     */

    @Override
    public void onAfterClientInfoDelete(KapuaId scopeId, ClientInfo clientInfo)
            throws KapuaIllegalArgumentException,
            EsConfigurationException,
            EsClientUnavailableException {
        messageStoreFacade.delete(scopeId, clientInfo.getFirstMessageId());
    }

    /*
     * ChannelInfo Store Mediator methods
     */

    @Override
    public void onBeforeChannelInfoDelete(ChannelInfo channelInfo)
            throws KapuaIllegalArgumentException,
            EsConfigurationException,
            EsQueryConversionException,
            EsClientUnavailableException {
        ChannelMatchPredicateImpl predicate = new ChannelMatchPredicateImpl(channelInfo.getChannel());

        MessageQueryImpl messageQuery = new MessageQueryImpl(channelInfo.getScopeId());
        messageQuery.setPredicate(predicate);
        messageStoreFacade.delete(messageQuery);

        MetricInfoQueryImpl metricInfoQuery = new MetricInfoQueryImpl(channelInfo.getScopeId());
        metricInfoQuery.setPredicate(predicate);
        metricInfoStoreFacade.delete(metricInfoQuery);
    }

    @Override
    public void onAfterChannelInfoDelete(ChannelInfo channelInfo) {
        // TODO Auto-generated method stub

    }

    /*
     * MetricInfo Store Mediator methods
     */

    @Override
    public void onAfterMetricInfoDelete(KapuaId scopeId, MetricInfo metricInfo) {
        // TODO Auto-generated method stub

    }
}
