/*******************************************************************************
 * Copyright (C) 2013  Stefan Schroeder
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either 
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public 
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jsprit.core.algorithm.state;

import jsprit.core.algorithm.listener.IterationStartsListener;
import jsprit.core.algorithm.recreate.listener.*;
import jsprit.core.algorithm.ruin.listener.RuinListener;
import jsprit.core.algorithm.ruin.listener.RuinListeners;
import jsprit.core.problem.Capacity;
import jsprit.core.problem.VehicleRoutingProblem;
import jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import jsprit.core.problem.job.Job;
import jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import jsprit.core.problem.solution.route.ReverseRouteActivityVisitor;
import jsprit.core.problem.solution.route.RouteActivityVisitor;
import jsprit.core.problem.solution.route.RouteVisitor;
import jsprit.core.problem.solution.route.VehicleRoute;
import jsprit.core.problem.solution.route.activity.ActivityVisitor;
import jsprit.core.problem.solution.route.activity.ReverseActivityVisitor;
import jsprit.core.problem.solution.route.activity.TourActivity;
import jsprit.core.problem.solution.route.state.RouteAndActivityStateGetter;
import jsprit.core.problem.solution.route.state.StateFactory;
import jsprit.core.problem.solution.route.state.StateFactory.StateId;
import jsprit.core.problem.vehicle.Vehicle;

import java.util.*;

/**
 * Manages states.
 * 
 * <p>Some condition, rules or constraints are stateful. This StateManager manages these states, i.e. it offers
 * methods to add, store and retrieve states based on the problem, vehicle-routes and tour-activities.
 * 
 * @author schroeder
 *
 */
public class StateManager implements RouteAndActivityStateGetter, IterationStartsListener, RuinListener, InsertionStartsListener, JobInsertedListener, InsertionEndsListener {


    static class States_ {
		
		private Map<StateId,Object> states = new HashMap<StateId,Object>();
		
		public <T> void putState(StateId id, Class<T> type, T state){
			states.put(id, type.cast(state));
		}
		
		public <T> T getState(StateId id, Class<T> type){
			if(states.containsKey(id)){
				return type.cast(states.get(id));
			}
			return null;
		}
		
		public boolean containsKey(StateId stateId){
			return states.containsKey(stateId);
		}
		
		public void clear(){
			states.clear();
		}
		
	}
	
	private States_ problemStates_ = new States_();
	
	private States_ defaultProblemStates_ = new States_();
	
	private RouteActivityVisitor routeActivityVisitor = new RouteActivityVisitor();
	
	private ReverseRouteActivityVisitor revRouteActivityVisitor = new ReverseRouteActivityVisitor();
	
	private Collection<RouteVisitor> routeVisitors = new ArrayList<RouteVisitor>();
	
	private RuinListeners ruinListeners = new RuinListeners();
	
	private InsertionListeners insertionListeners = new InsertionListeners();
	
	private Collection<StateUpdater> updaters = new ArrayList<StateUpdater>();
	
	private Map<StateId,Object> defaultRouteStates_ = new HashMap<StateId,Object>();
	
	private Map<StateId,Object> defaultActivityStates_ = new HashMap<StateId,Object>();
	
	private VehicleRoutingTransportCosts routingCosts;
	
	private boolean updateLoad = false;
	
	private boolean updateTWs = false;

    private int stateIndexCounter = 10;

    private Map<String,StateId> createdStateIds = new HashMap<String, StateId>();

    private int initialNuStates = 20;

    private int nuActivities;

    private int nuVehicleTypeKeys;

    private Object[][] activity_states;

    private Object[][][] vehicle_dependent_activity_states;

    private Object[][] route_states;

    private Object[][][] vehicle_dependent_route_states;

    private VehicleRoutingProblem vrp;

    int getMaxIndexOfVehicleTypeIdentifiers(){ return nuVehicleTypeKeys; }

    public StateId createStateId(String name){
        if(createdStateIds.containsKey(name)) return createdStateIds.get(name);
        if(stateIndexCounter>=activity_states[0].length){
            activity_states = new Object[vrp.getNuActivities()+1][stateIndexCounter+1];
            route_states = new Object[vrp.getNuActivities()+1][stateIndexCounter+1];
            vehicle_dependent_activity_states = new Object[nuActivities][nuVehicleTypeKeys][stateIndexCounter+1];
            vehicle_dependent_route_states = new Object[nuActivities][nuVehicleTypeKeys][stateIndexCounter+1];
        }
        StateId id = StateFactory.createId(name,stateIndexCounter);
        incStateIndexCounter();
        createdStateIds.put(name, id);
        return id;
    }

    private void incStateIndexCounter() {
        stateIndexCounter++;
    }

    private void addDefaultStates() {
		defaultActivityStates_.put(StateFactory.LOAD, Capacity.Builder.newInstance().build());
		defaultActivityStates_.put(StateFactory.COSTS, 0.);
		defaultActivityStates_.put(StateFactory.DURATION, 0.);
		defaultActivityStates_.put(StateFactory.FUTURE_MAXLOAD, Capacity.Builder.newInstance().build());
		defaultActivityStates_.put(StateFactory.PAST_MAXLOAD, Capacity.Builder.newInstance().build());
		
		defaultRouteStates_.put(StateFactory.LOAD, Capacity.Builder.newInstance().build());
		
		defaultRouteStates_.put(StateFactory.COSTS, 0.);
		defaultRouteStates_.put(StateFactory.DURATION, 0.);
		defaultRouteStates_.put(StateFactory.FUTURE_MAXLOAD, Capacity.Builder.newInstance().build());
		defaultRouteStates_.put(StateFactory.PAST_MAXLOAD, Capacity.Builder.newInstance().build());
		
		defaultRouteStates_.put(StateFactory.MAXLOAD, Capacity.Builder.newInstance().build());
		
		defaultRouteStates_.put(StateFactory.LOAD_AT_END, Capacity.Builder.newInstance().build());
		defaultRouteStates_.put(StateFactory.LOAD_AT_BEGINNING, Capacity.Builder.newInstance().build());
		
	}

    public StateManager(VehicleRoutingProblem vehicleRoutingProblem){
        this.routingCosts = vehicleRoutingProblem.getTransportCosts();
        this.vrp = vehicleRoutingProblem;
        nuActivities = Math.max(10,vrp.getNuActivities() + 1);
        nuVehicleTypeKeys = Math.max(3,getNuVehicleTypes(vrp) + 1);
        activity_states = new Object[nuActivities][initialNuStates];
        route_states = new Object[nuActivities][initialNuStates];
        vehicle_dependent_activity_states = new Object[nuActivities][nuVehicleTypeKeys][initialNuStates];
        vehicle_dependent_route_states = new Object[nuActivities][nuVehicleTypeKeys][initialNuStates];
        addDefaultStates();
    }

    private int getNuVehicleTypes(VehicleRoutingProblem vrp) {
        int maxIndex = 0;
        for(Vehicle v : vrp.getVehicles()){
            maxIndex = Math.max(maxIndex,v.getVehicleTypeIdentifier().getIndex());
        }
        return maxIndex;
    }

    public <T> void addDefaultProblemState(StateId stateId, Class<T> type, T defaultState){
		defaultProblemStates_.putState(stateId, type, defaultState); 
	}

	public <T> void putProblemState(StateId stateId, Class<T> type, T state){
		problemStates_.putState(stateId, type, state); 
	}
	
	public <T> T getProblemState(StateId stateId, Class<T> type){
		if(!problemStates_.containsKey(stateId)){
			return getDefaultProblemState(stateId, type);
		}
		return problemStates_.getState(stateId, type);
	}
	
	<T> T getDefaultProblemState(StateId stateId, Class<T> type){
		if(defaultProblemStates_.containsKey(stateId)) return defaultProblemStates_.getState(stateId, type); 
		return null;
	}
	
	/**
	 * Generic method to add a default route state.
	 * 
	 * <p>for example if you want to store 'maximum weight' at route-level, the default might be zero and you
	 * can add the default simply by coding <br>
	 * <code>addDefaultRouteState(StateFactory.createStateId("max_weight"), Integer.class, 0)</code>
	 * 
	 * @param stateId for which a default state is added
	 * @param type of state
	 * @param defaultState default state value
	 */
	public <T> void addDefaultRouteState(StateId stateId, Class<T> type, T defaultState){
		if(StateFactory.isReservedId(stateId)) StateFactory.throwReservedIdException(stateId.toString());
		defaultRouteStates_.put(stateId, type.cast(defaultState));
	}
	
	/**
	 * Generic method to add default activity state.
	 * 
	 * @param stateId for which a default state is added
	 * @param type of state
	 * @param defaultState default state value
	 */
	public <T> void addDefaultActivityState(StateId stateId, Class<T> type, T defaultState){
		if(StateFactory.isReservedId(stateId)) StateFactory.throwReservedIdException(stateId.toString());
		defaultActivityStates_.put(stateId, type.cast(defaultState));
	}
	
	/**
	 * Clears all states.
	 * 
	 */
	public void clear(){
        fill_twoDimArr(activity_states, null);
        fill_twoDimArr(route_states, null);
        fill_threeDimArr(vehicle_dependent_activity_states, null);
        fill_threeDimArr(vehicle_dependent_route_states, null);
		problemStates_.clear();
	}

    private void fill_threeDimArr(Object[][][] states, Object o) {
        for(Object[][] twoDimArr : states){
            for(Object[] oneDimArr : twoDimArr){
                Arrays.fill(oneDimArr,o);
            }
        }
    }

    private void fill_twoDimArr(Object[][] states, Object o) {
        for(Object[] rows : states){
            Arrays.fill(rows,o);
        }
    }

    /**
	 * Returns activity state of type 'type'.
	 * 
	 */
	@Override
	public <T> T getActivityState(TourActivity act, StateId stateId, Class<T> type) {
		if(act.getIndex()<0) return getDefaultTypedActivityState(act, stateId, type);
        T state;
        try{
           state = type.cast(activity_states[act.getIndex()][stateId.getIndex()]);
        }
        catch (ClassCastException e){
            throw getClassCastException(e,stateId,type.toString(),activity_states[act.getIndex()][stateId.getIndex()].getClass().toString());
        }
        if(state == null) return getDefaultTypedActivityState(act, stateId, type);
        return state;
	}

    public boolean hasActivityState(TourActivity act, Vehicle vehicle, StateId stateId){
        return vehicle_dependent_activity_states[act.getIndex()][vehicle.getVehicleTypeIdentifier().getIndex()][stateId.getIndex()] != null;
    }

    /**
     * Returns activity state of type 'type'.
     *
     */
    public <T> T getActivityState(TourActivity act, Vehicle vehicle, StateId stateId, Class<T> type) {
        if(act.getIndex()<0) return getDefaultTypedActivityState(act, stateId, type);
        T state;
        try {
            state = type.cast(vehicle_dependent_activity_states[act.getIndex()][vehicle.getVehicleTypeIdentifier().getIndex()][stateId.getIndex()]);
        }
        catch(ClassCastException e){
            Object state_class = vehicle_dependent_activity_states[act.getIndex()][vehicle.getVehicleTypeIdentifier().getIndex()][stateId.getIndex()];
            throw getClassCastException(e,stateId,type.toString(),state_class.getClass().toString());
        }
        if(state == null) return getDefaultTypedActivityState(act, stateId, type);
        return state;
    }

    private ClassCastException getClassCastException(ClassCastException e, StateId stateId, String requestedTypeClass, String memorizedTypeClass){
        return new ClassCastException(e + "\n" + "state with stateId '" + stateId.toString() + "' is of " + memorizedTypeClass + ". cannot cast it to " + requestedTypeClass + ".");
    }

	/**
	 * 
	 * @param act activity for which the state is requested
	 * @param stateId stateId of requested state
	 * @param type class of state value
	 * @return state value
	 */
	private <T> T getDefaultTypedActivityState(TourActivity act, StateId stateId, Class<T> type) {
		if(defaultActivityStates_.containsKey(stateId)){
			return type.cast(defaultActivityStates_.get(stateId));
		}
		if(stateId.equals(StateFactory.EARLIEST_OPERATION_START_TIME)){
			return type.cast(act.getTheoreticalEarliestOperationStartTime());
		}
		if(stateId.equals(StateFactory.LATEST_OPERATION_START_TIME)){
			return type.cast(act.getTheoreticalLatestOperationStartTime());
		}
		return null;
	}

	/**
	 * Return route state of type 'type'.
	 * 
	 * @return route-state
	 * @throws ClassCastException if state of route and stateId is of another type
	 */
	@Override
	public <T> T getRouteState(VehicleRoute route, StateId stateId, Class<T> type) {
        if(route.isEmpty()) return getDefaultTypedRouteState(stateId,type);
        T state;
        try{
            state = type.cast(route_states[route.getActivities().get(0).getIndex()][stateId.getIndex()]);
        }
        catch (ClassCastException e){
            throw getClassCastException(e,stateId,type.toString(),route_states[route.getActivities().get(0).getIndex()][stateId.getIndex()].getClass().toString());
        }
        if(state==null) return getDefaultTypedRouteState(stateId,type);
        return state;
	}

    public boolean hasRouteState(VehicleRoute route, Vehicle vehicle, StateId stateId) {
        return vehicle_dependent_route_states[route.getActivities().get(0).getIndex()][vehicle.getVehicleTypeIdentifier().getIndex()][stateId.getIndex()] != null;
    }

    public <T> T getRouteState(VehicleRoute route, Vehicle vehicle, StateId stateId, Class<T> type) {
        if(route.isEmpty()) return getDefaultTypedRouteState(stateId,type);
        T state;
        try{
           state = type.cast(vehicle_dependent_route_states[route.getActivities().get(0).getIndex()][vehicle.getVehicleTypeIdentifier().getIndex()][stateId.getIndex()]);
        }
        catch( ClassCastException e){
            throw getClassCastException(e, stateId, type.toString(), vehicle_dependent_route_states[route.getActivities().get(0).getIndex()][vehicle.getVehicleTypeIdentifier().getIndex()][stateId.getIndex()].getClass().toString());
        }
        if(state==null) return getDefaultTypedRouteState(stateId,type);
        return state;
    }

	private <T> T getDefaultTypedRouteState(StateId stateId, Class<T> type) {
		if(defaultRouteStates_.containsKey(stateId)){
			return type.cast(defaultRouteStates_.get(stateId));
		}
		return null;
	}


	/**
	 * Generic method to memorize state 'state' of type 'type' of act and stateId.
	 * 
	 * <p><b>For example: </b><br>
	 * <code>Capacity loadAtMyActivity = Capacity.Builder.newInstance().addCapacityDimension(0,10).build();<br>
	 * stateManager.putTypedActivityState(myActivity, StateFactory.createStateId("act-load"), Capacity.class, loadAtMyActivity);</code>
	 * <p>you can retrieve the load at myActivity by <br>
	 * <code>Capacity load = stateManager.getActivityState(myActivity, StateFactory.createStateId("act-load"), Capacity.class);</code>
	 * 
	 * @param act for which a new state should be memorized
	 * @param stateId stateId of state
	 * @param type class of state-value
	 * @param state state-value
     * @deprecated use putActivityState(...) instead
	 */
    @Deprecated
	public <T> void putTypedActivityState(TourActivity act, StateId stateId, Class<T> type, T state){
        if(stateId.getIndex()<10) throw new IllegalStateException("either you use a reserved stateId that is applied\n" +
                "internally or your stateId has been created without index, e.g. StateFactory.createId(stateName)\n" +
                " does not assign indeces thus do not use it anymore, but use\n " +
                "stateManager.createStateId(name)\n" +
                " instead.\n");
		putInternalTypedActivityState(act, stateId, state);
	}

    /**
     * Method to memorize state 'state' of type 'type' of act and stateId.
     *
     * <p><b>For example: </b><br>
     * <code>Capacity loadAtMyActivity = Capacity.Builder.newInstance().addCapacityDimension(0,10).build();<br>
     * stateManager.putTypedActivityState(myActivity, StateFactory.createStateId("act-load"), Capacity.class, loadAtMyActivity);</code>
     * <p>you can retrieve the load at myActivity by <br>
     * <code>Capacity load = stateManager.getActivityState(myActivity, StateFactory.createStateId("act-load"), Capacity.class);</code>
     *
     * @param act for which a new state should be memorized
     * @param stateId stateId of state
     * @param type class of state-value
     * @param state state-value
     */
    @Deprecated
    public <T> void putActivityState(TourActivity act, StateId stateId, Class<T> type, T state){
        if(stateId.getIndex()<10) throw new IllegalStateException("either you use a reserved stateId that is applied\n" +
                "internally or your stateId has been created without index, e.g. StateFactory.createId(stateName)\n" +
                " does not assign indeces thus do not use it anymore, but use\n " +
                "stateManager.createStateId(name)\n" +
                " instead.\n");
        putInternalTypedActivityState(act, stateId, state);
    }

    public <T> void putActivityState(TourActivity act, StateId stateId, T state){
        if(stateId.getIndex()<10) throw new IllegalStateException("either you use a reserved stateId that is applied\n" +
                "internally or your stateId has been created without index, e.g. StateFactory.createId(stateName)\n" +
                " does not assign indeces thus do not use it anymore, but use\n " +
                "stateManager.createStateId(name)\n" +
                " instead.\n");
        putInternalTypedActivityState(act, stateId, state);
    }

    public <T> void putActivityState(TourActivity act, Vehicle vehicle, StateId stateId, T state){
        if(stateId.getIndex()<10) throw new IllegalStateException("either you use a reserved stateId that is applied\n" +
                "internally or your stateId has been created without index, e.g. StateFactory.createId(stateName)\n" +
                " does not assign indeces thus do not use it anymore, but use\n " +
                "stateManager.createStateId(name)\n" +
                " instead.\n");
        putInternalTypedActivityState(act, vehicle, stateId, state);
    }

    private Object[][] resizeArr(Object[][] states, int newLength) {
        int oldSize = states.length;
        Object[][] new_states = new Object[newLength][stateIndexCounter];
        System.arraycopy(states,0,new_states,0,Math.min(oldSize,newLength));
        return new_states;
    }

    <T> void putInternalTypedActivityState(TourActivity act, StateId stateId, T state){
        activity_states[act.getIndex()][stateId.getIndex()]=state;
	}

    <T> void putInternalTypedActivityState(TourActivity act, Vehicle vehicle, StateId stateId, T state){
        vehicle_dependent_activity_states[act.getIndex()][vehicle.getVehicleTypeIdentifier().getIndex()][stateId.getIndex()]=state;
    }

	/**
	 * Generic method to memorize state 'state' of type 'type' of route and stateId.
	 * 
	 * <p><b>For example:</b> <br>
	 * <code>double totalRouteDuration = 100.0;<br>
	 * stateManager.putTypedActivityState(myRoute, StateFactory.createStateId("route-duration"), Double.class, totalRouteDuration);</code>
	 * <p>you can retrieve the duration of myRoute then by <br>
	 * <code>double totalRouteDuration = stateManager.getRouteState(myRoute, StateFactory.createStateId("route-duration"), Double.class);</code> 
	 * 
	 * @param route for which a state needs to be memorized
	 * @param stateId stateId of the state value to identify it
	 * @param type type of state
	 * @param state state value
     * @deprecated use putRouteState(...) instead
	 */
    @Deprecated
	public <T> void putTypedRouteState(VehicleRoute route, StateId stateId, Class<T> type, T state){
		putRouteState(route, stateId, state);
	}

    /**
     * Generic method to memorize state 'state' of type 'type' of route and stateId.
     *
     * <p><b>For example:</b> <br>
     * <code>double totalRouteDuration = 100.0;<br>
     * stateManager.putTypedActivityState(myRoute, StateFactory.createStateId("route-duration"), Double.class, totalRouteDuration);</code>
     * <p>you can retrieve the duration of myRoute then by <br>
     * <code>double totalRouteDuration = stateManager.getRouteState(myRoute, StateFactory.createStateId("route-duration"), Double.class);</code>
     *
     * @param route for which a state needs to be memorized
     * @param stateId stateId of the state value to identify it
     * @param state state value
     */
    public <T> void putRouteState(VehicleRoute route, StateId stateId, T state){
        if(stateId.getIndex()<10) StateFactory.throwReservedIdException(stateId.toString());
        putTypedInternalRouteState(route, stateId, state);
    }

    public <T> void putRouteState(VehicleRoute route, Vehicle vehicle, StateId stateId, T state){
        if(stateId.getIndex()<10) StateFactory.throwReservedIdException(stateId.toString());
        putTypedInternalRouteState(route, vehicle, stateId, state);
    }

    <T> void putTypedInternalRouteState(VehicleRoute route, StateId stateId, T state){
        if(route.isEmpty()) return;
        route_states[route.getActivities().get(0).getIndex()][stateId.getIndex()] = state;
    }

    <T> void putTypedInternalRouteState(VehicleRoute route, Vehicle vehicle, StateId stateId, T state){
        if(route.isEmpty()) return;
        vehicle_dependent_route_states[route.getActivities().get(0).getIndex()][vehicle.getVehicleTypeIdentifier().getIndex()][stateId.getIndex()] = state;
    }

	/**
	 * Adds state updater.
	 * 
	 * <p>Note that a state update occurs if route and/or activity states have changed, i.e. if jobs are removed
	 * or inserted into a route. Thus here, it is assumed that a state updater is either of type InsertionListener, 
	 * RuinListener, ActivityVisitor, ReverseActivityVisitor, RouteVisitor, ReverseRouteVisitor. 
	 * 
	 * <p>The following rule pertain for activity/route visitors:These visitors visits all activities/route in a route subsequently in two cases. First, if insertionStart (after ruinStrategies have removed activities from routes)
	 * and, second, if a job has been inserted and thus if a route has changed.
	 *  
	 * @param updater the update to be added
	 */
	public void addStateUpdater(StateUpdater updater){
		if(updater instanceof ActivityVisitor) addActivityVisitor((ActivityVisitor) updater);
		if(updater instanceof ReverseActivityVisitor) addActivityVisitor((ReverseActivityVisitor)updater);
		if(updater instanceof RouteVisitor) addRouteVisitor((RouteVisitor) updater);
		if(updater instanceof InsertionListener) addListener((InsertionListener) updater);
		if(updater instanceof RuinListener) addListener((RuinListener) updater);
		updaters.add(updater);
	}
	
	
	
	Collection<StateUpdater> getStateUpdaters(){
		return Collections.unmodifiableCollection(updaters);
	}
	
	/**
	 * Adds an activityVisitor.
	 * <p>This visitor visits all activities in a route subsequently in two cases. First, if insertionStart (after ruinStrategies have removed activities from routes)
	 * and, second, if a job has been inserted and thus if a route has changed. 
	 * 
	 * @param activityVistor the activity-visitor to be added
	 */
	 void addActivityVisitor(ActivityVisitor activityVistor){
		routeActivityVisitor.addActivityVisitor(activityVistor);
	}

	/**
	 * Adds an reverseActivityVisitor.
	 * <p>This reverseVisitor visits all activities in a route subsequently (starting from the end of the route) in two cases. First, if insertionStart (after ruinStrategies have removed activities from routes)
	 * and, second, if a job has been inserted and thus if a route has changed. 
	 * 
	 * @param activityVistor activityVisitor to add
	 */
	 void addActivityVisitor(ReverseActivityVisitor activityVistor){
		revRouteActivityVisitor.addActivityVisitor(activityVistor);
	}

	 void addRouteVisitor(RouteVisitor routeVisitor){
		routeVisitors.add(routeVisitor);
	}

	void addListener(RuinListener ruinListener){
		ruinListeners.addListener(ruinListener);
	}

	void removeListener(RuinListener ruinListener){
		ruinListeners.removeListener(ruinListener);
	}

	void addListener(InsertionListener insertionListener){
		insertionListeners.addListener(insertionListener);
	}

	void removeListener(InsertionListener insertionListener){
		insertionListeners.removeListener(insertionListener);
	}

	
	@Override
	public void informJobInserted(Job job2insert, VehicleRoute inRoute, double additionalCosts, double additionalTime) {
//		log.debug("insert " + job2insert + " in " + inRoute);
		insertionListeners.informJobInserted(job2insert, inRoute, additionalCosts, additionalTime);
		for(RouteVisitor v : routeVisitors){ v.visit(inRoute); }
		routeActivityVisitor.visit(inRoute);
		revRouteActivityVisitor.visit(inRoute);
	}

	@Override
	public void informInsertionStarts(Collection<VehicleRoute> vehicleRoutes,Collection<Job> unassignedJobs) {
		insertionListeners.informInsertionStarts(vehicleRoutes, unassignedJobs);
		for(VehicleRoute route : vehicleRoutes){ 
			for(RouteVisitor v : routeVisitors){ v.visit(route); }
			routeActivityVisitor.visit(route);
			revRouteActivityVisitor.visit(route);
		}
	}
	
	@Override
	public void informIterationStarts(int i, VehicleRoutingProblem problem, Collection<VehicleRoutingProblemSolution> solutions) {
		clear();
	}

	@Override
	public void ruinStarts(Collection<VehicleRoute> routes) {
		ruinListeners.ruinStarts(routes);
	}

	@Override
	public void ruinEnds(Collection<VehicleRoute> routes, Collection<Job> unassignedJobs) {
//		log.debug("ruin ends");
		ruinListeners.ruinEnds(routes, unassignedJobs);		
	}

	@Override
	public void removed(Job job, VehicleRoute fromRoute) {
		ruinListeners.removed(job, fromRoute);
	}

	@Override
	public void informInsertionEnds(Collection<VehicleRoute> vehicleRoutes) {
		insertionListeners.informInsertionEndsListeners(vehicleRoutes);
	}
	
	public void updateLoadStates() {
		if(!updateLoad){
			updateLoad=true;
			UpdateLoads updateLoads = new UpdateLoads(this);
			addActivityVisitor(updateLoads);
			addListener(updateLoads);
			addActivityVisitor(new UpdateMaxCapacityUtilisationAtActivitiesByLookingBackwardInRoute(this));
			addActivityVisitor(new UpdateMaxCapacityUtilisationAtActivitiesByLookingForwardInRoute(this));
			addActivityVisitor(new UpdateMaxCapacityUtilisationAtRoute(this));
		}
	}

	public void updateTimeWindowStates() {
		if(!updateTWs){
			updateTWs=true;
			addActivityVisitor(new UpdatePracticalTimeWindows(this, routingCosts));
		}
	}

	
}
