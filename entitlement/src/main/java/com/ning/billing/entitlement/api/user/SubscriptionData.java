/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.entitlement.api.user;

import com.ning.billing.catalog.api.ActionPolicy;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.Catalog;
import com.ning.billing.catalog.api.CatalogApiException;
import com.ning.billing.catalog.api.Plan;
import com.ning.billing.catalog.api.PlanPhase;
import com.ning.billing.catalog.api.PlanPhaseSpecifier;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.entitlement.api.user.SubscriptionFactory.SubscriptionBuilder;
import com.ning.billing.entitlement.api.user.SubscriptionTransition.SubscriptionTransitionType;
import com.ning.billing.entitlement.api.user.SubscriptionTransitionDataIterator.Kind;
import com.ning.billing.entitlement.api.user.SubscriptionTransitionDataIterator.Order;
import com.ning.billing.entitlement.api.user.SubscriptionTransitionDataIterator.TimeLimit;
import com.ning.billing.entitlement.api.user.SubscriptionTransitionDataIterator.Visibility;
import com.ning.billing.entitlement.events.EntitlementEvent;
import com.ning.billing.entitlement.events.phase.PhaseEvent;
import com.ning.billing.entitlement.events.user.ApiEvent;
import com.ning.billing.entitlement.events.user.ApiEventType;
import com.ning.billing.entitlement.exceptions.EntitlementError;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.clock.Clock;
import com.ning.billing.util.customfield.CustomField;
import com.ning.billing.util.customfield.CustomizableEntityBase;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

public class SubscriptionData extends CustomizableEntityBase implements Subscription {

    private final static Logger log = LoggerFactory.getLogger(SubscriptionData.class);

    private final Clock clock;
    private final SubscriptionApiService apiService;
    //
    // Final subscription fields
    //
    private final UUID bundleId;
    private final DateTime startDate;
    private final DateTime bundleStartDate;
    private final ProductCategory category;

    //
    // Those can be modified through non User APIs, and a new Subscription object would be created
    //
    private final long activeVersion;
    private final DateTime chargedThroughDate;
    private final DateTime paidThroughDate;

    //
    // User APIs (create, change, cancel,...) will recompute those each time,
    // so the user holding that subscription object get the correct state when
    // the call completes
    //
    private LinkedList<SubscriptionTransitionData> transitions;

    // Transient object never returned at the API
    public SubscriptionData(SubscriptionBuilder builder) {
        this(builder, null, null);
    }

    public SubscriptionData(SubscriptionBuilder builder, @Nullable SubscriptionApiService apiService,
                            @Nullable Clock clock) {
        super(builder.getId(), null, null);
        this.apiService = apiService;
        this.clock = clock;
        this.bundleId = builder.getBundleId();
        this.startDate = builder.getStartDate();
        this.bundleStartDate = builder.getBundleStartDate();
        this.category = builder.getCategory();
        this.activeVersion = builder.getActiveVersion();
        this.chargedThroughDate = builder.getChargedThroughDate();
        this.paidThroughDate = builder.getPaidThroughDate();
    }

    @Override
    public String getObjectName() {
        return "Subscription";
    }

    @Override
    public void saveFieldValue(String fieldName, String fieldValue, CallContext context) {
        super.setFieldValue(fieldName, fieldValue);
        apiService.commitCustomFields(this, context);
    }

    @Override
    public void saveFields(List<CustomField> fields, CallContext context) {
        super.setFields(fields);
        apiService.commitCustomFields(this, context);
    }

    @Override
    public void clearPersistedFields(CallContext context) {
        super.clearFields();
        apiService.commitCustomFields(this, context);
    }

    @Override
    public UUID getBundleId() {
        return bundleId;
    }

    @Override
    public DateTime getStartDate() {
        return startDate;
    }

    @Override
    public SubscriptionState getState() {
        return (getPreviousTransition() == null) ? null : getPreviousTransition().getNextState();
    }

    @Override
    public PlanPhase getCurrentPhase() {
        return (getPreviousTransition() == null) ? null : getPreviousTransition().getNextPhase();
    }


    @Override
    public Plan getCurrentPlan() {
        return (getPreviousTransition() == null) ? null : getPreviousTransition().getNextPlan();
    }

    @Override
    public String getCurrentPriceList() {
        return (getPreviousTransition() == null) ? null : getPreviousTransition().getNextPriceList();
    }


    @Override
    public DateTime getEndDate() {
        SubscriptionTransition latestTransition = getPreviousTransition();
        if (latestTransition.getNextState() == SubscriptionState.CANCELLED) {
            return latestTransition.getEffectiveTransitionTime();
        }
        return null;
    }


    @Override
    public void cancel(DateTime requestedDate, boolean eot) throws EntitlementUserApiException  {
        apiService.cancel(this, requestedDate, eot);
    }

    @Override
    public void uncancel() throws EntitlementUserApiException {
        apiService.uncancel(this);
    }

    @Override
    public void changePlan(String productName, BillingPeriod term,
            String priceList, DateTime requestedDate) throws EntitlementUserApiException {
        apiService.changePlan(this, productName, term, priceList, requestedDate);
    }

    @Override
    public void recreate(PlanPhaseSpecifier spec, DateTime requestedDate)
            throws EntitlementUserApiException {
        apiService.recreatePlan(this, spec, requestedDate);
    }

    public List<SubscriptionTransition> getBillingTransitions() {

        if (transitions == null) {
            return Collections.emptyList();
        }
        List<SubscriptionTransition> result = new ArrayList<SubscriptionTransition>();
        SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(clock, transitions,
                Order.ASC_FROM_PAST, Kind.BILLING, Visibility.ALL, TimeLimit.ALL);
        while (it.hasNext()) {
            result.add(it.next());
        }
        return result;
    }

    @Override
    public SubscriptionTransition getPendingTransition() {

        if (transitions == null) {
            return null;
        }
        SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(clock, transitions,
                Order.ASC_FROM_PAST, Kind.ENTITLEMENT, Visibility.ALL, TimeLimit.FUTURE_ONLY);
        return it.hasNext() ? it.next() : null;
    }

    @Override
    public SubscriptionTransition getPreviousTransition() {
        if (transitions == null) {
            return null;
        }
        SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(clock, transitions,
                Order.DESC_FROM_FUTURE, Kind.ENTITLEMENT, Visibility.FROM_DISK_ONLY, TimeLimit.PAST_OR_PRESENT_ONLY);
        return it.hasNext() ? it.next() : null;
    }

    public SubscriptionTransition getTransitionFromEvent(EntitlementEvent event) {
        if (transitions == null || event == null) {
            return null;
        }
        for (SubscriptionTransition cur : transitions) {
            if (cur.getId().equals(event.getId())) {
                return cur;
            }
        }
        return null;
    }

    public long getActiveVersion() {
        return activeVersion;
    }

    @Override
    public ProductCategory getCategory() {
        return category;
    }

    public DateTime getBundleStartDate() {
        return bundleStartDate;
    }

    @Override
    public DateTime getChargedThroughDate() {
        return chargedThroughDate;
    }

    @Override
    public DateTime getPaidThroughDate() {
        return paidThroughDate;
    }

    public SubscriptionTransitionData getInitialTransitionForCurrentPlan() {
        if (transitions == null) {
            throw new EntitlementError(String.format("No transitions for subscription %s", getId()));
        }


        SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(clock, transitions,
                Order.DESC_FROM_FUTURE, Kind.ENTITLEMENT, Visibility.ALL, TimeLimit.PAST_OR_PRESENT_ONLY);
        while (it.hasNext()) {
            SubscriptionTransitionData cur = it.next();
            if (cur.getTransitionType() == SubscriptionTransitionType.CREATE ||
                    cur.getTransitionType() == SubscriptionTransitionType.RE_CREATE ||
                    cur.getTransitionType() == SubscriptionTransitionType.CHANGE ||
                    cur.getTransitionType() == SubscriptionTransitionType.MIGRATE_ENTITLEMENT) {
                return cur;
            }
        }
        throw new EntitlementError(String.format("Failed to find InitialTransitionForCurrentPlan id = %s", getId().toString()));
    }

    public boolean isSubscriptionFutureCancelled() {
        if (transitions == null) {
            return false;
        }
        SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(clock, transitions,
                Order.ASC_FROM_PAST, Kind.ENTITLEMENT, Visibility.ALL, TimeLimit.FUTURE_ONLY);
        while (it.hasNext()) {
            SubscriptionTransitionData cur = it.next();
            if (cur.getTransitionType() == SubscriptionTransitionType.CANCEL) {
                return true;
            }
        }
        return false;
    }

    public DateTime getPlanChangeEffectiveDate(ActionPolicy policy, DateTime requestedDate) {

        if (policy == ActionPolicy.IMMEDIATE) {
            return requestedDate;
        }
        if (policy != ActionPolicy.END_OF_TERM) {
            throw new EntitlementError(String.format("Unexpected policy type %s", policy.toString()));
        }

        if (chargedThroughDate == null) {
            return requestedDate;
        } else {
            return chargedThroughDate.isBefore(requestedDate) ? requestedDate : chargedThroughDate;
        }
    }

    public DateTime getCurrentPhaseStart() {

        if (transitions == null) {
            throw new EntitlementError(String.format("No transitions for subscription %s", getId()));
        }
        SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(clock, transitions,
                Order.DESC_FROM_FUTURE, Kind.ENTITLEMENT, Visibility.ALL, TimeLimit.PAST_OR_PRESENT_ONLY);
        while (it.hasNext()) {
            SubscriptionTransitionData cur = it.next();

            if (cur.getTransitionType() == SubscriptionTransitionType.PHASE ||
                    cur.getTransitionType() == SubscriptionTransitionType.CREATE ||
                    cur.getTransitionType() == SubscriptionTransitionType.RE_CREATE ||
                    cur.getTransitionType() == SubscriptionTransitionType.CHANGE ||
                    cur.getTransitionType() == SubscriptionTransitionType.MIGRATE_ENTITLEMENT) {
                return cur.getEffectiveTransitionTime();
            }
        }
        throw new EntitlementError(String.format("Failed to find CurrentPhaseStart id = %s", getId().toString()));
    }

    public void rebuildTransitions(final List<EntitlementEvent> events, final Catalog catalog) {

        if (events == null) {
            return;
        }

        SubscriptionState nextState = null;
        String nextPlanName = null;
        String nextPhaseName = null;
        String nextPriceList = null;

        SubscriptionState previousState = null;
        String previousPriceList = null;

        transitions = new LinkedList<SubscriptionTransitionData>();
        Plan previousPlan = null;
        PlanPhase previousPhase = null;

        for (final EntitlementEvent cur : events) {

            if (!cur.isActive() || cur.getActiveVersion() < activeVersion) {
                continue;
            }

            ApiEventType apiEventType = null;

            boolean isFromDisk = true;

            switch (cur.getType()) {

            case PHASE:
                PhaseEvent phaseEV = (PhaseEvent) cur;
                nextPhaseName = phaseEV.getPhase();
                break;

            case API_USER:
                ApiEvent userEV = (ApiEvent) cur;
                apiEventType = userEV.getEventType();
                isFromDisk = userEV.isFromDisk();
                switch(apiEventType) {
                case MIGRATE_BILLING:
                case MIGRATE_ENTITLEMENT:
                case CREATE:
                case RE_CREATE:
                    previousState = null;
                    previousPlan = null;
                    previousPhase = null;
                    previousPriceList = null;
                    nextState = SubscriptionState.ACTIVE;
                    nextPlanName = userEV.getEventPlan();
                    nextPhaseName = userEV.getEventPlanPhase();
                    nextPriceList = userEV.getPriceList();
                    break;
                case CHANGE:
                    nextPlanName = userEV.getEventPlan();
                    nextPhaseName = userEV.getEventPlanPhase();
                    nextPriceList = userEV.getPriceList();
                    break;
                case CANCEL:
                    nextState = SubscriptionState.CANCELLED;
                    nextPlanName = null;
                    nextPhaseName = null;
                    break;
                case UNCANCEL:
                    break;
                default:
                    throw new EntitlementError(String.format("Unexpected UserEvent type = %s",
                            userEV.getEventType().toString()));
                }
                break;
            default:
                throw new EntitlementError(String.format("Unexpected Event type = %s",
                        cur.getType()));
            }


            Plan nextPlan = null;
            PlanPhase nextPhase = null;
            try {
                nextPlan = (nextPlanName != null) ? catalog.findPlan(nextPlanName, cur.getRequestedDate(), getStartDate()) : null;
                nextPhase = (nextPhaseName != null) ? catalog.findPhase(nextPhaseName, cur.getRequestedDate(), getStartDate()) : null;
            } catch (CatalogApiException e) {
                log.error(String.format("Failed to build transition for subscription %s", id), e);
            }
            SubscriptionTransitionData transition =
                new SubscriptionTransitionData(cur.getId(),
                        id,
                        bundleId,
                        cur.getType(),
                        apiEventType,
                        cur.getRequestedDate(),
                        cur.getEffectiveDate(),
                        previousState,
                        previousPlan,
                        previousPhase,
                        previousPriceList,
                        nextState,
                        nextPlan,
                        nextPhase,
                        nextPriceList,
                        cur.getTotalOrdering(),
                        isFromDisk);
            transitions.add(transition);

            previousState = nextState;
            previousPlan = nextPlan;
            previousPhase = nextPhase;
            previousPriceList = nextPriceList;
        }
    }

}
