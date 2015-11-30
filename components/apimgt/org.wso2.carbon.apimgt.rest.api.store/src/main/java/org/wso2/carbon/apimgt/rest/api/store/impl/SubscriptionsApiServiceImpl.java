/*
 *
 *  Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.wso2.carbon.apimgt.rest.api.store.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIConsumer;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIMgtAuthorizationFailedException;
import org.wso2.carbon.apimgt.api.SubscriptionAlreadyExistingException;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Application;
import org.wso2.carbon.apimgt.api.model.SubscribedAPI;
import org.wso2.carbon.apimgt.api.model.Subscriber;
import org.wso2.carbon.apimgt.api.model.SubscriptionResponse;
import org.wso2.carbon.apimgt.rest.api.store.SubscriptionsApiService;
import org.wso2.carbon.apimgt.rest.api.store.dto.SubscriptionDTO;
import org.wso2.carbon.apimgt.rest.api.store.dto.SubscriptionListDTO;
import org.wso2.carbon.apimgt.rest.api.store.utils.RestAPIStoreUtils;
import org.wso2.carbon.apimgt.rest.api.util.RestApiConstants;
import org.wso2.carbon.apimgt.rest.api.util.exception.InternalServerErrorException;
import org.wso2.carbon.apimgt.rest.api.store.utils.mappings.APIMappingUtil;
import org.wso2.carbon.apimgt.rest.api.store.utils.mappings.SubscriptionMappingUtil;
import org.wso2.carbon.apimgt.rest.api.util.utils.RestApiUtil;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.ws.rs.core.Response;

/**
 * This is the service implementation class for Store subscription related operations
 */
public class SubscriptionsApiServiceImpl extends SubscriptionsApiService {

    private static final Log log = LogFactory.getLog(SubscriptionsApiServiceImpl.class);

    /**
     * Get all subscriptions that are of user or shared subscriptions of the user's group.
     * <p/>
     * If apiId is specified this will return the subscribed applications of that api
     * If application id is specified this will return the api subscriptions of that application
     *
     * @param apiId         api identifier
     * @param applicationId application identifier
     * @param groupId       group id
     * @param offset        starting index of the subscription list
     * @param limit         max num of subscriptions returned
     * @param accept        Accept header value
     * @param ifNoneMatch   If-None-Match header value
     * @return matched subscriptions as a list of SubscriptionDTOs
     */
    @Override
    public Response subscriptionsGet(String apiId, String applicationId, String groupId, Integer offset,
            Integer limit, String accept, String ifNoneMatch) {
        String username = RestApiUtil.getLoggedInUsername();
        String tenantDomain = RestApiUtil.getLoggedInUserTenantDomain();
        Subscriber subscriber = new Subscriber(username);
        Set<SubscribedAPI> subscriptions;
        List<SubscribedAPI> subscribedAPIList = new ArrayList<>();

        //pre-processing
        limit = limit != null ? limit : RestApiConstants.PAGINATION_LIMIT_DEFAULT;
        offset = offset != null ? offset : RestApiConstants.PAGINATION_OFFSET_DEFAULT;

        // currently groupId is taken from the user so that groupId coming as a query parameter is not honored.
        // As a improvement, we can check admin privileges of the user and honor groupId.
        groupId = RestAPIStoreUtils.getLoggedInUserGroupId();

        try {
            APIConsumer apiConsumer = RestApiUtil.getConsumer(username);
            SubscriptionListDTO subscriptionListDTO;
            if (!StringUtils.isEmpty(apiId)) {
                // this will fail with an authorization failed exception if user does not have permission to access the API
                APIIdentifier apiIdentifier = APIMappingUtil.getAPIIdentifierFromApiIdOrUUID(apiId, tenantDomain);
                subscriptions = apiConsumer.getSubscribedIdentifiers(subscriber, apiIdentifier, groupId);
                //sort by application name
                subscribedAPIList.addAll(subscriptions);
                Collections.sort(subscribedAPIList, new Comparator<SubscribedAPI>() {
                    @Override
                    public int compare(SubscribedAPI o1, SubscribedAPI o2) {
                        return o1.getApplication().getName().compareTo(o2.getApplication().getName());
                    }
                });

                subscriptionListDTO = SubscriptionMappingUtil
                        .fromSubscriptionListToDTO(subscribedAPIList, limit, offset);
                SubscriptionMappingUtil.setPaginationParamsForAPIId(subscriptionListDTO, apiId, groupId, limit, offset,
                        subscriptions.size());

                return Response.ok().entity(subscriptionListDTO).build();
            } else if (!StringUtils.isEmpty(applicationId)) {
                Application application = apiConsumer.getApplicationByUUID(applicationId);

                if (application == null) {
                    throw RestApiUtil.buildNotFoundException(RestApiConstants.RESOURCE_APPLICATION, applicationId);
                }

                if (!RestAPIStoreUtils.isUserAccessAllowedForApplication(application)) {
                    throw RestApiUtil.buildForbiddenException(RestApiConstants.RESOURCE_APPLICATION, applicationId);
                }

                subscriptions = apiConsumer.getSubscribedAPIs(subscriber, application.getName(),
                        application.getGroupId());
                subscribedAPIList.addAll(subscriptions);
                //sort by api
                Collections.sort(subscribedAPIList, new Comparator<SubscribedAPI>() {
                    @Override
                    public int compare(SubscribedAPI o1, SubscribedAPI o2) {
                        return o1.getApiId().getApiName().compareTo(o2.getApiId().getApiName());
                    }
                });
                subscriptionListDTO = SubscriptionMappingUtil.fromSubscriptionListToDTO(subscribedAPIList, limit,
                        offset);
                SubscriptionMappingUtil.setPaginationParamsForApplicationId(subscriptionListDTO, applicationId, limit,
                        offset, subscriptions.size());

                return Response.ok().entity(subscriptionListDTO).build();

            } else {
                //neither apiId nor applicationId is given
                throw RestApiUtil.buildBadRequestException("Either applicationId or apiId should be available");
            }
        } catch (APIManagementException e) {
            if (RestApiUtil.isDueToAuthorizationFailure(e)) {
                throw RestApiUtil.buildForbiddenException(RestApiConstants.RESOURCE_API, apiId);
            } else if (RestApiUtil.isDueToResourceNotFound(e)) {
                throw RestApiUtil.buildNotFoundException(RestApiConstants.RESOURCE_API, apiId);
            } else {
                handleException("Error while getting subscriptions of the user " + username, e);
                return null;
            }
        }
    }

    /**
     * Creates a new subscriptions with the details specified in the body parameter
     *
     * @param body new subscription details
     * @param contentType Content-Type header
     * @return newly added subscription as a SubscriptionDTO if successful
     */
    @Override
    public Response subscriptionsPost(SubscriptionDTO body, String contentType) {
        String username = RestApiUtil.getLoggedInUsername();
        String tenantDomain = RestApiUtil.getLoggedInUserTenantDomain();
        APIConsumer apiConsumer;
        try {
            apiConsumer = RestApiUtil.getConsumer(username);
            String applicationId = body.getApplicationId();

            //check whether user is permitted to access the API. If the API does not exist, 
            // this will throw a APIMgtResourceNotFoundException
            if (!RestAPIStoreUtils.isUserAccessAllowedForAPI(body.getApiId(), tenantDomain)) {
                throw RestApiUtil.buildForbiddenException(RestApiConstants.RESOURCE_API, body.getApiId());
            }
            APIIdentifier apiIdentifier = APIMappingUtil.getAPIIdentifierFromApiIdOrUUID(body.getApiId(), tenantDomain);

            Application application = apiConsumer.getApplicationByUUID(applicationId);
            if (application == null) {
                //required application not found
                throw RestApiUtil.buildNotFoundException(RestApiConstants.RESOURCE_APPLICATION, applicationId);
            }

            if (!RestAPIStoreUtils.isUserAccessAllowedForApplication(application)) {
                //application access failure occurred
                throw RestApiUtil.buildForbiddenException(RestApiConstants.RESOURCE_APPLICATION, applicationId);
            }

            //Validation for allowed throttling tiers and Tenant based validation for subscription. If failed this will
            //  throw an APIMgtAuthorizationFailedException with the reason as the message
            RestAPIStoreUtils.checkSubscriptionAllowed(apiIdentifier, body.getTier());

            apiIdentifier.setTier(body.getTier());
            SubscriptionResponse subscriptionResponse = apiConsumer
                    .addSubscription(apiIdentifier, username, application.getId());
            SubscribedAPI addedSubscribedAPI = apiConsumer
                    .getSubscriptionByUUID(subscriptionResponse.getSubscriptionUUID());
            SubscriptionDTO addedSubscriptionDTO = SubscriptionMappingUtil.fromSubscriptionToDTO(addedSubscribedAPI);
            return Response.created(
                    new URI(RestApiConstants.RESOURCE_PATH_SUBSCRIPTIONS + "/" + addedSubscribedAPI.getUUID())).entity(
                    addedSubscriptionDTO).build();

        } catch (APIMgtAuthorizationFailedException e) {
            //this occurs when the api:application:tier mapping is not allowed. The reason for the message is taken from
            // the message of the exception e
            throw RestApiUtil.buildForbiddenException(e.getMessage());
        } catch (SubscriptionAlreadyExistingException e) {
            throw RestApiUtil.buildConflictException(
                    "Specified subscription already exists for API " + body.getApiId() + " for application " + body
                            .getApplicationId());
        } catch (APIManagementException | URISyntaxException e) {
            if (RestApiUtil.isDueToResourceNotFound(e)) {
                //this happens when the specified API identifier does not exist
                throw RestApiUtil.buildNotFoundException(RestApiConstants.RESOURCE_API, body.getApiId());
            } else {
                //unhandled exception
                handleException("Error while adding the subscription API:" + body.getApiId() + ", application:" + body
                        .getApplicationId() + ", tier:" + body.getTier(), e);
                return null;
            }
        }
    }

    /**
     * Gets a subscription by identifier
     *
     * @param subscriptionId  subscription identifier
     * @param accept          Accept header value
     * @param ifNoneMatch     If-None-Match header value
     * @param ifModifiedSince If-Modified-Since header value
     * @return matched subscription as a SubscriptionDTO
     */
    @Override
    public Response subscriptionsSubscriptionIdGet(String subscriptionId, String accept, String ifNoneMatch,
            String ifModifiedSince) {
        String username = RestApiUtil.getLoggedInUsername();
        APIConsumer apiConsumer;
        try {
            apiConsumer = RestApiUtil.getConsumer(username);
            SubscribedAPI subscribedAPI = apiConsumer.getSubscriptionByUUID(subscriptionId);
            if (subscribedAPI != null) {
                if (RestAPIStoreUtils.isUserAccessAllowedForSubscription(subscribedAPI)) {
                    SubscriptionDTO subscriptionDTO = SubscriptionMappingUtil.fromSubscriptionToDTO(subscribedAPI);
                    return Response.ok().entity(subscriptionDTO).build();
                } else {
                    throw RestApiUtil.buildForbiddenException(RestApiConstants.RESOURCE_SUBSCRIPTION, subscriptionId);
                }
            } else {
                throw RestApiUtil.buildNotFoundException(RestApiConstants.RESOURCE_SUBSCRIPTION, subscriptionId);
            }
        } catch (APIManagementException e) {
            handleException("Error while getting subscription with id " + subscriptionId, e);
            return null;
        }
    }

    /**
     * Deletes the subscription matched to subscription id
     *
     * @param subscriptionId    subscription identifier
     * @param ifMatch           If-Match header value
     * @param ifUnmodifiedSince If-Unmodified-Since header value
     * @return 200 response if successfully deleted the subscription
     */
    @Override
    public Response subscriptionsSubscriptionIdDelete(String subscriptionId, String ifMatch, String ifUnmodifiedSince) {
        String username = RestApiUtil.getLoggedInUsername();
        APIConsumer apiConsumer;
        try {
            apiConsumer = RestApiUtil.getConsumer(username);
            SubscribedAPI subscribedAPI = apiConsumer.getSubscriptionByUUID(subscriptionId);
            if (subscribedAPI != null) {
                if (RestAPIStoreUtils.isUserAccessAllowedForSubscription(subscribedAPI)) {
                    apiConsumer.removeSubscription(subscribedAPI);
                } else {
                    throw RestApiUtil.buildForbiddenException(RestApiConstants.RESOURCE_SUBSCRIPTION, subscriptionId);
                }
            } else {
                throw RestApiUtil.buildNotFoundException(RestApiConstants.RESOURCE_SUBSCRIPTION, subscriptionId);
            }
            return Response.ok().build();
        } catch (APIManagementException e) {
            handleException("Error while deleting subscription with id " + subscriptionId, e);
            return null;
        }
    }

    private void handleException(String msg, Throwable t) throws InternalServerErrorException {
        log.error(msg, t);
        throw new InternalServerErrorException(t);
    }

}
