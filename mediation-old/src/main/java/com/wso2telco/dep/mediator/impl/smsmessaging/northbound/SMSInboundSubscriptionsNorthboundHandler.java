/*
 *
 *  Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
 * /
 */
package com.wso2telco.dep.mediator.impl.smsmessaging.northbound;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wso2telco.core.dbutils.fileutils.FileReader;
import com.wso2telco.dep.mediator.OperatorEndpoint;
import com.wso2telco.dep.mediator.entity.smsmessaging.CallbackReference;
import com.wso2telco.dep.mediator.entity.smsmessaging.northbound.DestinationAddresses;
import com.wso2telco.dep.mediator.entity.smsmessaging.northbound.NorthboundSubscribeRequest;
import com.wso2telco.dep.mediator.entity.smsmessaging.southbound.SouthboundSubscribeRequest;
import com.wso2telco.dep.mediator.entity.smsmessaging.southbound.Subscription;
import com.wso2telco.dep.mediator.impl.smsmessaging.SMSExecutor;
import com.wso2telco.dep.mediator.impl.smsmessaging.SMSHandler;
import com.wso2telco.dep.mediator.internal.ApiUtils;
import com.wso2telco.dep.mediator.internal.Type;
import com.wso2telco.dep.mediator.internal.UID;
import com.wso2telco.dep.mediator.mediationrule.OriginatingCountryCalculatorIDD;
import com.wso2telco.dep.mediator.service.SMSMessagingService;
import com.wso2telco.dep.mediator.util.DataPublisherConstants;
import com.wso2telco.dep.mediator.util.FileNames;
import com.wso2telco.dep.mediator.util.HandlerUtils;
import com.wso2telco.dep.oneapivalidation.exceptions.CustomException;
import com.wso2telco.dep.oneapivalidation.service.IServiceValidate;
import com.wso2telco.dep.oneapivalidation.service.impl.smsmessaging.northbound.ValidateNBSubscription;
import com.wso2telco.dep.operatorservice.model.OperatorApplicationDTO;
import com.wso2telco.dep.operatorservice.model.OperatorSubscriptionDTO;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.json.JSONException;
import org.json.JSONObject;
import org.wso2.carbon.utils.CarbonUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SMSInboundSubscriptionsNorthboundHandler implements SMSHandler {

	/** The log. */
	private static Log log = LogFactory.getLog(SMSInboundSubscriptionsNorthboundHandler.class);

	/** The Constant API_TYPE. */
	private static final String API_TYPE = "smsmessaging";

	/** The occi. */
	private OriginatingCountryCalculatorIDD occi;

	/** The smsMessagingDAO. */
	private SMSMessagingService smsMessagingService;

	/** The executor. */
	private SMSExecutor executor;

	/** The api utils. */
	private ApiUtils apiUtils;

	/** Configuration file */
	private String file = CarbonUtils.getCarbonConfigDirPath() + File.separator + FileNames.MEDIATOR_CONF_FILE.getFileName();

	/** Configuration Map */
	private Map<String, String> mediatorConfMap;

	/** JSON builder */
	private Gson gson = new GsonBuilder().serializeNulls().create();

	public SMSInboundSubscriptionsNorthboundHandler(SMSExecutor executor) {

		this.executor = executor;
		occi = new OriginatingCountryCalculatorIDD();
		smsMessagingService = new SMSMessagingService();
		apiUtils = new ApiUtils();
		mediatorConfMap = new FileReader().readPropertyFile(file);
	}

	@Override
	public boolean validate(String httpMethod, String requestPath, JSONObject jsonBody, MessageContext context)
			throws Exception {

		if (!httpMethod.equalsIgnoreCase("POST")) {

			((Axis2MessageContext) context).getAxis2MessageContext().setProperty("HTTP_SC", 405);
			throw new Exception("Method not allowed");
		}

		context.setProperty(DataPublisherConstants.OPERATION_TYPE, 205);
		IServiceValidate validator;

		validator = new ValidateNBSubscription();
		validator.validateUrl(requestPath);
		validator.validate(jsonBody.toString());

		return true;
	}

	@Override
	public boolean handle(MessageContext context) throws Exception {

		String requestid = UID.getUniqueID(Type.RETRIVSUB.getCode(), context, executor.getApplicationid());

		HashMap<String, String> jwtDetails = apiUtils.getJwtTokenDetails(context);
		JSONObject jsonBody = executor.getJsonBody();
		JSONObject jsondstaddr = jsonBody.getJSONObject("subscription");

		String orgclientcl = "";
		if (!jsondstaddr.isNull("clientCorrelator")) {
			orgclientcl = jsondstaddr.getString("clientCorrelator");
		}

		String serviceProvider = jwtDetails.get("subscriber");
		log.debug("subscriber Name : " + serviceProvider);

		NorthboundSubscribeRequest nbSubsrequst = gson.fromJson(jsonBody.toString(), NorthboundSubscribeRequest.class);
		String origNotiUrl = nbSubsrequst.getSubscription().getCallbackReference().getNotifyURL();

		String origCallbackData = nbSubsrequst.getSubscription().getCallbackReference().getCallbackData();

		String notificationFormat = nbSubsrequst.getSubscription().getNotificationFormat();

		List<OperatorEndpoint> endpoints = occi.getAPIEndpointsByApp(API_TYPE, executor.getSubResourcePath(),
				executor.getValidoperators());

		Map<String, OperatorEndpoint> operatorMap = new HashMap<String, OperatorEndpoint>();

		for (OperatorEndpoint endpoint : endpoints) {

			operatorMap.put(endpoint.getOperator(), endpoint);

		}

		Integer moSubscriptionId = smsMessagingService.subscriptionEntry(
				nbSubsrequst.getSubscription().getCallbackReference().getNotifyURL(), serviceProvider);

		String subsEndpoint = mediatorConfMap.get("hubSubsGatewayEndpoint") + "/" + moSubscriptionId;
//		jsondstaddr.getJSONObject("callbackReference").put("notifyURL", subsEndpoint);
//		jsondstaddr.put("clientCorrelator", orgclientcl + ":" + requestid);

		nbSubsrequst.getSubscription().getCallbackReference().setNotifyURL(subsEndpoint);

		nbSubsrequst.getSubscription().setClientCorrelator(orgclientcl + ":" + requestid);


		log.debug("subscription northbound request body : " + gson.toJson(nbSubsrequst));

		DestinationAddresses[] destinationAddresses = nbSubsrequst.getSubscription().getDestinationAddresses();

//		List<OperatorSubscriptionDTO> domainsubs = new ArrayList<OperatorSubscriptionDTO>();
//		SouthboundSubscribeRequest sbSubsresponse = null;

		for (DestinationAddresses destinationAddressesObj : destinationAddresses) {

			if (operatorMap.containsKey(destinationAddressesObj.getOperatorCode())) {

				OperatorEndpoint endpoint = operatorMap.get(destinationAddressesObj.getOperatorCode());
				String url = endpoint.getEndpointref().getAddress();

				destinationAddressesObj.setToAddress(url);

				destinationAddressesObj.setAuthorizationHeader("Bearer " + executor.getAccessToken(endpoint.getOperator(), context));

				destinationAddressesObj.setOperatorId(endpoint.getOperatorId());

				log.debug("operator name: " + endpoint.getOperator());

			} else {

				log.error("OperatorEndpoint not found. Operator Not Provisioned: " + destinationAddressesObj.getOperatorCode());

				destinationAddressesObj.setToAddress("Not Provisioned");
			}
		}

		nbSubsrequst.getSubscription().setDestinationAddresses(destinationAddresses);

		String requestStr = gson.toJson(nbSubsrequst);

		JsonUtil.newJsonPayload(((Axis2MessageContext) context).getAxis2MessageContext(), requestStr, true, true);

		HandlerUtils.setHandlerProperty(context,this.getClass().getSimpleName());

		String ResourceUrlPrefix = mediatorConfMap.get("hubGateway");
		context.setProperty("responseResourceURL", ResourceUrlPrefix + executor.getResourceUrl() + "/" + moSubscriptionId);

		context.setProperty("subscriptionID", moSubscriptionId);
		context.setProperty("original_notifyUrl", origNotiUrl);
		context.setProperty("original_callbackData", origCallbackData);

		context.setProperty("original_clientCorrelator", orgclientcl);

		context.setProperty("original_notificationFormat", notificationFormat);

		return true;
	}



/*

		for (OperatorEndpoint endpoint : endpoints) {

			for (int i = 0; i < destinationAddresses.length; i++) {
				if (destinationAddresses[i].getOperatorCode().equalsIgnoreCase(endpoint.getOperator())) {
					log.debug("operator name: " + endpoint.getOperator());
					SouthboundSubscribeRequest sbSubsrequst = new SouthboundSubscribeRequest();
					Subscription sbrequest = new Subscription();
					CallbackReference callbackReference = new CallbackReference();

					callbackReference
							.setCallbackData(nbSubsrequst.getSubscription().getCallbackReference().getCallbackData());
					callbackReference.setNotifyURL(subsEndpoint);
					sbrequest.setCallbackReference(callbackReference);
					sbrequest.setClientCorrelator(orgclientcl + ":" + requestid);
					sbrequest.setNotificationFormat(nbSubsrequst.getSubscription().getNotificationFormat());
					sbrequest.setCriteria(destinationAddresses[i].getCriteria());
					sbrequest.setDestinationAddress(destinationAddresses[i].getDestinationAddress());
					sbSubsrequst.setSubscription(sbrequest);

					String sbRequestBody = removeResourceURL(gson.toJson(sbSubsrequst));
					log.debug("subscription southbound request body of " + endpoint.getOperator() + " operator: "
							+ sbRequestBody);

					String notifyres = executor.makeRequest(endpoint, endpoint.getEndpointref().getAddress(),
							sbRequestBody, true, context,false);

					log.debug("subscription southbound response body of " + endpoint.getOperator() + " operator: "
							+ notifyres);

					if (notifyres == null) {
						destinationAddresses[i].setStatus("Failed");

					} else {
						// plugin exception handling
						sbSubsresponse = gson.fromJson(notifyres, SouthboundSubscribeRequest.class);
						if (sbSubsresponse.getSubscription() == null) {

							destinationAddresses[i].setStatus("NotCreated");
						} else {
							domainsubs.add(new OperatorSubscriptionDTO(endpoint.getOperator(),
									sbSubsresponse.getSubscription().getResourceURL()));
							destinationAddresses[i].setStatus("Created");
						}
					}
					break;
				}
			}
		}

		smsMessagingService.operatorSubsEntry(domainsubs, moSubscriptionId);

		String ResourceUrlPrefix = mediatorConfMap.get("hubGateway");

		DestinationAddresses[] responseDestinationAddresses = new DestinationAddresses[destinationAddresses.length];
		int destinationAddressesCount = 0;
		int successResultCount = 0;
		for (DestinationAddresses destinationAddressesResult : destinationAddresses) {
			String subscriptionStatus = destinationAddressesResult.getStatus();
			if (subscriptionStatus == null) {
				destinationAddressesResult.setStatus("Failed");
			} else if (subscriptionStatus.equals("Created")) {
				successResultCount++;
			}
			responseDestinationAddresses[destinationAddressesCount] = destinationAddressesResult;
			destinationAddressesCount++;
		}

		if (successResultCount == 0) {
			throw new CustomException("POL0299", "", new String[] { "Error registering subscription" });
		}

		nbSubsrequst.getSubscription().setDestinationAddresses(responseDestinationAddresses);
		nbSubsrequst.getSubscription()
				.setResourceURL(ResourceUrlPrefix + executor.getResourceUrl() + "/" + moSubscriptionId);
		nbSubsrequst.getSubscription().setClientCorrelator(orgclientcl);
		nbSubsrequst.getSubscription().getCallbackReference().setNotifyURL(origNotiUrl);

		String nbResponseBody = gson.toJson(nbSubsrequst);

		log.debug("subscription northbound response body : " + nbResponseBody);

		executor.removeHeaders(context);
		((Axis2MessageContext) context).getAxis2MessageContext().setProperty("HTTP_SC", 201);
		executor.setResponse(context, nbResponseBody.toString());

*/

	/*private String removeResourceURL(String request) {

		String sbrequestString = "";

		try {

			JSONObject objJSONObject = new JSONObject(request);
			JSONObject objSubscriptionRequest = (JSONObject) objJSONObject.get("subscription");
			objSubscriptionRequest.remove("resourceURL");

			sbrequestString = objSubscriptionRequest.toString();
		} catch (JSONException ex) {

			log.error("error in removeResourceURL" + ex.getMessage());
			throw new CustomException("POL0299", "", new String[] { "Error registering subscription" });
		}

		return "{\"subscription\":" + sbrequestString + "}";
	}*/
}
