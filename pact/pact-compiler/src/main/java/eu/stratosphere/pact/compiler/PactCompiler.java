/***********************************************************************************************************************
 *
 * Copyright (C) 2010 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/

package eu.stratosphere.pact.compiler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import eu.stratosphere.nephele.configuration.ConfigConstants;
import eu.stratosphere.nephele.configuration.Configuration;
import eu.stratosphere.nephele.configuration.GlobalConfiguration;
import eu.stratosphere.nephele.instance.InstanceType;
import eu.stratosphere.nephele.instance.InstanceTypeDescription;
import eu.stratosphere.nephele.ipc.RPC;
import eu.stratosphere.nephele.net.NetUtils;
import eu.stratosphere.nephele.protocols.ExtendedManagementProtocol;
import eu.stratosphere.pact.common.contract.GenericDataSink;
import eu.stratosphere.pact.common.contract.GenericDataSource;
import eu.stratosphere.pact.common.plan.Plan;
import eu.stratosphere.pact.common.plan.Visitor;
import eu.stratosphere.pact.common.util.PactConfigConstants;
import eu.stratosphere.pact.compiler.costs.CostEstimator;
import eu.stratosphere.pact.compiler.costs.DefaultCostEstimator;
import eu.stratosphere.pact.compiler.pactrecord.PactRecordPostPass;
import eu.stratosphere.pact.compiler.plan.CoGroupNode;
import eu.stratosphere.pact.compiler.plan.CrossNode;
import eu.stratosphere.pact.compiler.plan.DataSinkNode;
import eu.stratosphere.pact.compiler.plan.DataSourceNode;
import eu.stratosphere.pact.compiler.plan.MapNode;
import eu.stratosphere.pact.compiler.plan.MatchNode;
import eu.stratosphere.pact.compiler.plan.OptimizerNode;
import eu.stratosphere.pact.compiler.plan.ReduceNode;
import eu.stratosphere.pact.compiler.plan.SinkJoiner;
import eu.stratosphere.pact.compiler.plan.candidate.Channel;
import eu.stratosphere.pact.compiler.plan.candidate.OptimizedPlan;
import eu.stratosphere.pact.compiler.plan.candidate.PlanNode;
import eu.stratosphere.pact.compiler.plan.candidate.SinkPlanNode;
import eu.stratosphere.pact.compiler.plan.candidate.SourcePlanNode;
import eu.stratosphere.pact.compiler.postpass.OptimizerPostPass;
import eu.stratosphere.pact.generic.contract.Contract;
import eu.stratosphere.pact.generic.contract.GenericCoGroupContract;
import eu.stratosphere.pact.generic.contract.GenericCrossContract;
import eu.stratosphere.pact.generic.contract.GenericMapContract;
import eu.stratosphere.pact.generic.contract.GenericMatchContract;
import eu.stratosphere.pact.generic.contract.GenericReduceContract;

/**
 * The optimizer that takes the user specified pact plan and creates an optimized plan that contains
 * exact descriptions about how the physical execution will take place. It first translates the user
 * pact program into an internal optimizer representation and then chooses between different alternatives
 * for shipping strategies and local strategies.
 * <p>
 * The basic principle is taken from optimizer works in systems such as Volcano/Cascades and Selinger/System-R/DB2. The
 * optimizer walks from the sinks down, generating interesting properties, and ascends from the sources generating
 * alternative plans, pruning against the interesting properties.
 * <p>
 * The optimizer also assigns the memory to the individual tasks. This is currently done in a very simple fashion: All
 * sub-tasks that need memory (e.g. reduce or match) are given an equal share of memory.
 */
public class PactCompiler {

	// ------------------------------------------------------------------------
	// Constants
	// ------------------------------------------------------------------------

	/**
	 * Compiler hint key for the input channel's shipping strategy. This String is a key to the contract's stub
	 * parameters. The corresponding value tells the compiler which shipping strategy to use for the input channel.
	 * If the contract has two input channels, the shipping strategy is applied to both input channels.
	 */
	public static final String HINT_SHIP_STRATEGY = "INPUT_SHIP_STRATEGY";

	/**
	 * Compiler hint key for the <b>first</b> input channel's shipping strategy. This String is a key to
	 * the contract's stub parameters. The corresponding value tells the compiler which shipping strategy
	 * to use for the <b>first</b> input channel. Only applicable to contracts with two inputs.
	 */
	public static final String HINT_SHIP_STRATEGY_FIRST_INPUT = "INPUT_LEFT_SHIP_STRATEGY";

	/**
	 * Compiler hint key for the <b>second</b> input channel's shipping strategy. This String is a key to
	 * the contract's stub parameters. The corresponding value tells the compiler which shipping strategy
	 * to use for the <b>second</b> input channel. Only applicable to contracts with two inputs.
	 */
	public static final String HINT_SHIP_STRATEGY_SECOND_INPUT = "INPUT_RIGHT_SHIP_STRATEGY";

	/**
	 * Value for the shipping strategy compiler hint that enforces a hash-partition strategy.
	 * 
	 * @see #HINT_SHIP_STRATEGY
	 * @see #HINT_SHIP_STRATEGY_FIRST_INPUT
	 * @see #HINT_SHIP_STRATEGY_SECOND_INPUT
	 */
	public static final String HINT_SHIP_STRATEGY_REPARTITION_HASH = "SHIP_REPARTITION_HASH";
	
	/**
	 * Value for the shipping strategy compiler hint that enforces a range-partition strategy.
	 * 
	 * @see #HINT_SHIP_STRATEGY
	 * @see #HINT_SHIP_STRATEGY_FIRST_INPUT
	 * @see #HINT_SHIP_STRATEGY_SECOND_INPUT
	 */
	public static final String HINT_SHIP_STRATEGY_REPARTITION_RANGE = "SHIP_REPARTITION_RANGE";

	/**
	 * Value for the shipping strategy compiler hint that enforces a <b>broadcast</b> strategy on the
	 * input channel.
	 * 
	 * @see #HINT_SHIP_STRATEGY
	 * @see #HINT_SHIP_STRATEGY_FIRST_INPUT
	 * @see #HINT_SHIP_STRATEGY_SECOND_INPUT
	 */
	public static final String HINT_SHIP_STRATEGY_BROADCAST = "SHIP_BROADCAST";

	/**
	 * Value for the shipping strategy compiler hint that enforces a <b>Forward</b> strategy on the
	 * input channel, i.e. no redistribution of any kind.
	 * 
	 * @see #HINT_SHIP_STRATEGY
	 * @see #HINT_SHIP_STRATEGY_FIRST_INPUT
	 * @see #HINT_SHIP_STRATEGY_SECOND_INPUT
	 */
	public static final String HINT_SHIP_STRATEGY_FORWARD = "SHIP_FORWARD";

	/**
	 * Compiler hint key for the contract's local strategy. This String is a key to the contract's stub
	 * parameters. The corresponding value tells the compiler which local strategy to use to process the
	 * data inside one partition.
	 * <p>
	 * This hint is ignored by contracts that do not have a local strategy (such as <i>Map</i>), or by contracts that
	 * have no choice in their local strategy (such as <i>Cross</i>).
	 */
	public static final String HINT_LOCAL_STRATEGY = "LOCAL_STRATEGY";

	/**
	 * Value for the local strategy compiler hint that enforces a <b>sort based</b> local strategy.
	 * For example, a <i>Reduce</i> contract will sort the data to group it.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_SORT = "LOCAL_STRATEGY_SORT";
	
	/**
	 * Value for the local strategy compiler hint that enforces a <b>sort based</b> local strategy.
	 * During sorting a combine method is repeatedly applied to reduce the data volume.
	 * For example, a <i>Reduce</i> contract will sort the data to group it.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_COMBINING_SORT = "LOCAL_STRATEGY_COMBINING_SORT";
	
	/**
	 * Value for the local strategy compiler hint that enforces a <b>sort merge based</b> local strategy on both
	 * inputs with subsequent merging of inputs. 
	 * For example, a <i>Match</i> or <i>CoGroup</i> contract will use a sort-merge strategy to find pairs 
	 * of matching keys.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_SORT_BOTH_MERGE = "LOCAL_STRATEGY_SORT_BOTH_MERGE";
	
	/**
	 * Value for the local strategy compiler hint that enforces a <b>sort merge based</b> local strategy.
	 * The the first input is sorted, the second input is assumed to be sorted. After sorting both inputs are merged. 
	 * For example, a <i>Match</i> or <i>CoGroup</i> contract will use a sort-merge strategy to find pairs 
	 * of matching keys.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_SORT_FIRST_MERGE = "LOCAL_STRATEGY_SORT_FIRST_MERGE";
	
	/**
	 * Value for the local strategy compiler hint that enforces a <b>sort merge based</b> local strategy.
	 * The the second input is sorted, the first input is assumed to be sorted. After sorting both inputs are merged. 
	 * For example, a <i>Match</i> or <i>CoGroup</i> contract will use a sort-merge strategy to find pairs 
	 * of matching keys.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_SORT_SECOND_MERGE = "LOCAL_STRATEGY_SORT_SECOND_MERGE";
	
	/**
	 * Value for the local strategy compiler hint that enforces a <b>merge based</b> local strategy.
	 * Both inputs are assumed to be sorted and are merged. 
	 * For example, a <i>Match</i> or <i>CoGroup</i> contract will use a merge strategy to find pairs 
	 * of matching keys.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_MERGE = "LOCAL_STRATEGY_MERGE";

	
	/**
	 * Value for the local strategy compiler hint that enforces a <b>hash based</b> local strategy.
	 * For example, a <i>Match</i> contract will use a hybrid-hash-join strategy to find pairs of
	 * matching keys. The <b>first</b> input will be used to build the hash table, the second input will be
	 * used to probe the table.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_HASH_BUILD_FIRST = "LOCAL_STRATEGY_HASH_BUILD_FIRST";

	/**
	 * Value for the local strategy compiler hint that enforces a <b>hash based</b> local strategy.
	 * For example, a <i>Match</i> contract will use a hybrid-hash-join strategy to find pairs of
	 * matching keys. The <b>second</b> input will be used to build the hash table, the first input will be
	 * used to probe the table.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_HASH_BUILD_SECOND = "LOCAL_STRATEGY_HASH_BUILD_SECOND";

	/**
	 * Value for the local strategy compiler hint that chooses the outer side of the <b>nested-loop</b> local strategy.
	 * A <i>Cross</i> contract will process the data of the <b>first</b> input in the outer-loop of the nested loops.
	 * Hence, the data of the first input will be is streamed though, while the data of the second input is stored on
	 * disk
	 * and repeatedly read.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_NESTEDLOOP_STREAMED_OUTER_FIRST = "LOCAL_STRATEGY_NESTEDLOOP_STREAMED_OUTER_FIRST";

	/**
	 * Value for the local strategy compiler hint that chooses the outer side of the <b>nested-loop</b> local strategy.
	 * A <i>Cross</i> contract will process the data of the <b>second</b> input in the outer-loop of the nested loops.
	 * Hence, the data of the second input will be is streamed though, while the data of the first input is stored on
	 * disk
	 * and repeatedly read.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_NESTEDLOOP_STREAMED_OUTER_SECOND = "LOCAL_STRATEGY_NESTEDLOOP_STREAMED_OUTER_SECOND";

	/**
	 * Value for the local strategy compiler hint that chooses the outer side of the <b>nested-loop</b> local strategy.
	 * A <i>Cross</i> contract will process the data of the <b>first</b> input in the outer-loop of the nested loops.
	 * Further more, the first input, being the outer side, will be processed in blocks, and for each block, the second
	 * input,
	 * being the inner side, will read repeatedly from disk.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_NESTEDLOOP_BLOCKED_OUTER_FIRST = "LOCAL_STRATEGY_NESTEDLOOP_BLOCKED_OUTER_FIRST";

	/**
	 * Value for the local strategy compiler hint that chooses the outer side of the <b>nested-loop</b> local strategy.
	 * A <i>Cross</i> contract will process the data of the <b>second</b> input in the outer-loop of the nested loops.
	 * Further more, the second input, being the outer side, will be processed in blocks, and for each block, the first
	 * input,
	 * being the inner side, will read repeatedly from disk.
	 * 
	 * @see #HINT_LOCAL_STRATEGY
	 */
	public static final String HINT_LOCAL_STRATEGY_NESTEDLOOP_BLOCKED_OUTER_SECOND = "LOCAL_STRATEGY_NESTEDLOOP_BLOCKED_OUTER_SECOND";
	
	/**
	 * The log handle that is used by the compiler to log messages.
	 */
	public static final Log LOG = LogFactory.getLog(PactCompiler.class);
	
	/**
	 * the amount of memory for TempTasks in MiBytes.
	 */
	public static final int DEFAULT_TEMP_TASK_MEMORY = 4;

	// ------------------------------------------------------------------------
	// Members
	// ------------------------------------------------------------------------

	/**
	 * The statistics object used to obtain statistics, such as input sizes,
	 * for the cost estimation process.
	 */
	private final DataStatistics statistics;

	/**
	 * The cost estimator used by the compiler.
	 */
	private final CostEstimator costEstimator;

	/**
	 * The connection used to connect to the job-manager.
	 */
	private final InetSocketAddress jobManagerAddress;

	/**
	 * The maximum number of machines (instances) to use, per the configuration.
	 */
	private final int maxMachines;

	/**
	 * The default degree of parallelism for jobs compiled by this compiler.
	 */
	private final int defaultDegreeOfParallelism;

	/**
	 * The maximum number of subtasks that should share an instance.
	 */
	private final int maxIntraNodeParallelism;

	// ------------------------------------------------------------------------
	// Constructor & Setup
	// ------------------------------------------------------------------------

	/**
	 * Creates a new compiler instance. The compiler has no access to statistics about the
	 * inputs and can hence not determine any properties. It will perform all optimization with
	 * unknown sizes and default to the most robust strategy to fulfill the PACTs. The
	 * compiler also uses conservative default estimates for the operator costs, since
	 * it has no access to another cost estimator.
	 * <p>
	 * The address of the job manager (to obtain system characteristics) is determined via the global configuration.
	 */
	public PactCompiler() {
		this(null, new DefaultCostEstimator());
	}

	/**
	 * Creates a new compiler instance that uses the statistics object to determine properties about the input.
	 * Given those statistics, the compiler can make better choices for the execution strategies.
	 * as if no filesystem was given. The compiler uses conservative default estimates for the operator costs, since
	 * it has no access to another cost estimator.
	 * <p>
	 * The address of the job manager (to obtain system characteristics) is determined via the global configuration.
	 * 
	 * @param stats
	 *        The statistics to be used to determine the input properties.
	 */
	public PactCompiler(DataStatistics stats) {
		this(stats, new DefaultCostEstimator());
	}

	/**
	 * Creates a new compiler instance. The compiler has no access to statistics about the
	 * inputs and can hence not determine any properties. It will perform all optimization with
	 * unknown sizes and default to the most robust strategy to fulfill the PACTs. It uses
	 * however the given cost estimator to compute the costs of the individual operations.
	 * <p>
	 * The address of the job manager (to obtain system characteristics) is determined via the global configuration.
	 * 
	 * @param estimator
	 *        The <tt>CostEstimator</tt> to use to cost the individual operations.
	 */
	public PactCompiler(CostEstimator estimator) {
		this(null, estimator);
	}

	/**
	 * Creates a new compiler instance that uses the statistics object to determine properties about the input.
	 * Given those statistics, the compiler can make better choices for the execution strategies.
	 * as if no filesystem was given. It uses the given cost estimator to compute the costs of the individual
	 * operations.
	 * <p>
	 * The address of the job manager (to obtain system characteristics) is determined via the global configuration.
	 * 
	 * @param stats
	 *        The statistics to be used to determine the input properties.
	 * @param estimator
	 *        The <tt>CostEstimator</tt> to use to cost the individual operations.
	 */
	public PactCompiler(DataStatistics stats, CostEstimator estimator) {
		this(stats, estimator, null);
	}

	/**
	 * Creates a new compiler instance that uses the statistics object to determine properties about the input.
	 * Given those statistics, the compiler can make better choices for the execution strategies.
	 * as if no filesystem was given. It uses the given cost estimator to compute the costs of the individual
	 * operations.
	 * <p>
	 * The given socket-address is used to connect to the job manager to obtain system characteristics, like available
	 * memory. If that parameter is null, then the address is obtained from the global configuration.
	 * 
	 * @param stats
	 *        The statistics to be used to determine the input properties.
	 * @param estimator
	 *        The <tt>CostEstimator</tt> to use to cost the individual operations.
	 * @param jobManagerConnection
	 *        The address of the job manager that is queried for system characteristics.
	 */
	public PactCompiler(DataStatistics stats, CostEstimator estimator, InetSocketAddress jobManagerConnection) {
		this.statistics = stats;
		this.costEstimator = estimator;

		Configuration config = GlobalConfiguration.getConfiguration();

		// determine the maximum number of instances to use
		this.maxMachines = config.getInteger(PactConfigConstants.MAXIMUM_NUMBER_MACHINES_KEY,
			PactConfigConstants.DEFAULT_MAX_NUMBER_MACHINES);

		// determine the default parallelization degree
		this.defaultDegreeOfParallelism = config.getInteger(PactConfigConstants.DEFAULT_PARALLELIZATION_DEGREE_KEY,
			PactConfigConstants.DEFAULT_PARALLELIZATION_DEGREE);

		// determine the default intra-node parallelism
		int maxInNodePar = config.getInteger(PactConfigConstants.PARALLELIZATION_MAX_INTRA_NODE_DEGREE_KEY,
			PactConfigConstants.DEFAULT_MAX_INTRA_NODE_PARALLELIZATION_DEGREE);
		if (maxInNodePar == 0 || maxInNodePar < -1) {
			LOG.error("Invalid maximum degree of intra-node parallelism: " + maxInNodePar +
				". Ignoring parameter.");
			maxInNodePar = PactConfigConstants.DEFAULT_MAX_INTRA_NODE_PARALLELIZATION_DEGREE;
		}
		this.maxIntraNodeParallelism = maxInNodePar;

		// assign the connection to the job-manager
		if (jobManagerConnection != null) {
			this.jobManagerAddress = jobManagerConnection;
		} else {
			final String address = config.getString(ConfigConstants.JOB_MANAGER_IPC_ADDRESS_KEY, null);
			if (address == null) {
				throw new CompilerException(
					"Cannot find address to job manager's RPC service in the global configuration.");
			}

			final int port = GlobalConfiguration.getInteger(ConfigConstants.JOB_MANAGER_IPC_PORT_KEY,
				ConfigConstants.DEFAULT_JOB_MANAGER_IPC_PORT);
			if (port < 0) {
				throw new CompilerException(
					"Cannot find port to job manager's RPC service in the global configuration.");
			}

			this.jobManagerAddress = new InetSocketAddress(address, port);
		}
	}

	// ------------------------------------------------------------------------
	//                               Compilation
	// ------------------------------------------------------------------------

	/**
	 * Translates the given pact plan in to an OptimizedPlan, where all nodes have their local strategy assigned
	 * and all channels have a shipping strategy assigned. The compiler connects to the job manager to obtain information
	 * about the available instances and their memory and then chooses an instance type to schedule the execution on.
	 * <p>
	 * The compilation process itself goes through several phases:
	 * <ol>
	 * <li>Create <tt>OptimizerNode</tt> representations of the PACTs, assign parallelism and compute size estimates.</li>
	 * <li>Compute interesting properties and auxiliary structures.</li>
	 * <li>Enumerate plan alternatives. This cannot be done in the same step as the interesting property computation (as
	 * opposed to the Database approaches), because we support plans that are not trees.</li>
	 * </ol>
	 * 
	 * @param pactPlan The PACT plan to be translated.
	 * @return The optimized plan.
	 * @throws CompilerException
	 *         Thrown, if the plan is invalid or the optimizer encountered an inconsistent
	 *         situation during the compilation process.
	 */
	public OptimizedPlan compile(Plan pactPlan) throws CompilerException {
		// -------------------- try to get the connection to the job manager ----------------------
		// --------------------------to obtain instance information --------------------------------
		return compile(pactPlan, getInstanceTypeInfo(), new PactRecordPostPass());
	}
	
	public OptimizedPlan compile(Plan pactPlan, OptimizerPostPass postPasser) throws CompilerException {
		// -------------------- try to get the connection to the job manager ----------------------
		// --------------------------to obtain instance information --------------------------------
		return compile(pactPlan, getInstanceTypeInfo(), postPasser);
	}
	
	public OptimizedPlan compile(Plan pactPlan, InstanceTypeDescription type) throws CompilerException {
		return compile(pactPlan, type, new PactRecordPostPass());
	}
	
	/**
	 * Translates the given pact plan in to an OptimizedPlan, where all nodes have their local strategy assigned
	 * and all channels have a shipping strategy assigned. The process goes through several phases:
	 * <ol>
	 * <li>Create <tt>OptimizerNode</tt> representations of the PACTs, assign parallelism and compute size estimates.</li>
	 * <li>Compute interesting properties and auxiliary structures.</li>
	 * <li>Enumerate plan alternatives. This cannot be done in the same step as the interesting property computation (as
	 * opposed to the Database approaches), because we support plans that are not trees.</li>
	 * </ol>
	 * 
	 * @param pactPlan The PACT plan to be translated.
	 * @param type The instance type to schedule the execution on. Used also to determine the amount of memory
	 *             available to the tasks.
	 * @param postPasser The function to be used for post passing the optimizer's plan and setting the
	 *                   data type specific serialization routines.
	 * @return The optimized plan.
	 * 
	 * @throws CompilerException
	 *         Thrown, if the plan is invalid or the optimizer encountered an inconsistent
	 *         situation during the compilation process.
	 */
	public OptimizedPlan compile(Plan pactPlan, InstanceTypeDescription type, OptimizerPostPass postPasser) throws CompilerException
	{
		if (LOG.isDebugEnabled()) {
			LOG.debug("Beginning compilation of PACT program '" + pactPlan.getJobName() + '\'');
		}
		
		final String instanceName = type.getInstanceType().getIdentifier();
		
		// we subtract some percentage of the memory to accommodate for rounding errors
		final long memoryPerInstance = (long) (type.getHardwareDescription().getSizeOfFreeMemory() * 0.96f);
		final int numInstances = type.getMaximumNumberOfAvailableInstances();
		
		// determine the maximum number of machines to use
		int maxMachinesJob = pactPlan.getMaxNumberMachines();

		if (maxMachinesJob < 1) {
			maxMachinesJob = this.maxMachines;
		} else if (this.maxMachines >= 1) {
			// check if the program requested more than the global config allowed
			if (maxMachinesJob > this.maxMachines && LOG.isWarnEnabled()) {
				LOG.warn("Maximal number of machines specified in PACT program (" + maxMachinesJob
					+ ") exceeds the maximum number in the global configuration (" + this.maxMachines
					+ "). Using the value given in the global configuration.");
			}

			maxMachinesJob = Math.min(maxMachinesJob, this.maxMachines);
		}

		// adjust the maximum number of machines the the number of available instances
		if (maxMachinesJob < 1) {
			maxMachinesJob = numInstances;
		} else if (maxMachinesJob > numInstances) {
			maxMachinesJob = numInstances;
			if (LOG.isInfoEnabled()) {
				LOG.info("Maximal number of machines decreased to " + maxMachinesJob +
					" because no more instances are available.");
			}
		}

		// set the default degree of parallelism
		int defaultParallelism = pactPlan.getDefaultParallelism() > 0 ?
			pactPlan.getDefaultParallelism() : this.defaultDegreeOfParallelism;
		
		if (this.maxIntraNodeParallelism > 0) {
			if (defaultParallelism < 1) {
				defaultParallelism = maxMachinesJob * this.maxIntraNodeParallelism;
			}
			else if (defaultParallelism > maxMachinesJob * this.maxIntraNodeParallelism) {
				int oldParallelism = defaultParallelism;
				defaultParallelism = maxMachinesJob * this.maxIntraNodeParallelism;

				if (LOG.isInfoEnabled()) {
					LOG.info("Decreasing default degree of parallelism from " + oldParallelism +
						" to " + defaultParallelism + " to fit a maximum number of " + maxMachinesJob +
						" instances with a intra-parallelism of " + this.maxIntraNodeParallelism);
				}
			}
		} else if (defaultParallelism < 1) {
			defaultParallelism = maxMachinesJob;
			if (LOG.isInfoEnabled()) {
				LOG.info("No default parallelism specified. Using default parallelism of " + defaultParallelism + " (One task per instance)");
			}
		}

		// log the output
		if (LOG.isDebugEnabled()) {
			LOG.debug("Using a default degree of parallelism of " + defaultParallelism +
				", a maximum intra-node parallelism of " + this.maxIntraNodeParallelism + '.');
			if (this.maxMachines > 0) {
				LOG.debug("The execution is limited to a maximum number of " + maxMachinesJob + " machines.");
			}

		}

		// the first step in the compilation is to create the optimizer plan representation
		// this step does the following:
		// 1) It creates an optimizer plan node for each pact
		// 2) It connects them via channels
		// 3) It looks for hints about local strategies and channel types and
		// sets the types and strategies accordingly
		// 4) It makes estimates about the data volume of the data sources and
		// propagates those estimates through the plan

		GraphCreatingVisitor graphCreator = new GraphCreatingVisitor(this.statistics, maxMachinesJob, defaultParallelism, true);
		pactPlan.accept(graphCreator);

		// if we have a plan with multiple data sinks, add logical optimizer nodes that have two data-sinks as children
		// each until we have only a single root node. This allows to transparently deal with the nodes with
		// multiple outputs
		OptimizerNode rootNode;
		if (graphCreator.sinks.size() == 1) {
			rootNode = graphCreator.sinks.get(0);
		} else if (graphCreator.sinks.size() > 1) {
			Iterator<DataSinkNode> iter = graphCreator.sinks.iterator();
			rootNode = iter.next();
			int id = graphCreator.getId();

			while (iter.hasNext()) {
				rootNode = new SinkJoiner(rootNode, iter.next());
				rootNode.SetId(id++);
			}
		} else {
			throw new CompilerException("The plan encountered when generating alternatives has no sinks.");
		}

		// now that we have all nodes created and recorded which ones consume memory, tell the nodes their minimal
		// guaranteed memory, for further cost estimations. we assume an equal distribution of memory among consumer tasks
		
		rootNode.accept(new MemoryDistributer(
			graphCreator.getMemoryConsumerCount() == 0 ? 0 : memoryPerInstance / graphCreator.getMemoryConsumerCount()));
		
		// Now that the previous step is done, the next step is to traverse the graph again for the two
		// steps that cannot directly be performed during the plan enumeration, because we are dealing with DAGs
		// rather than a trees. That requires us to deviate at some points from the classical DB optimizer algorithms.
		//
		// 1) propagate the interesting properties top-down through the graph
		// 2) Track information about nodes with multiple outputs that are later on reconnected in a node with
		// multiple inputs.
		InterestingPropertyVisitor propsVisitor = new InterestingPropertyVisitor(this.costEstimator);
		rootNode.accept(propsVisitor);
		
		BranchesVisitor branchingVisitor = new BranchesVisitor();
		rootNode.accept(branchingVisitor);

		// the final step is now to generate the actual plan alternatives
		List<PlanNode> bestPlan = rootNode.getAlternativePlans(this.costEstimator);

		if (bestPlan.size() != 1) {
			throw new CompilerException("Error in compiler: more than one best plan was created!");
		}

		// check if the best plan's root is a data sink (single sink plan)
		// if so, directly take it. if it is a sink joiner node, get its contained sinks
		PlanNode bestPlanRoot = bestPlan.get(0);
		List<SinkPlanNode> bestPlanSinks = new ArrayList<SinkPlanNode>(4);

		if (bestPlanRoot instanceof SinkPlanNode) {
			bestPlanSinks.add((SinkPlanNode) bestPlanRoot);
//		} else if (bestPlanRoot instanceof SinkJoiner) {
//			((SinkJoiner) bestPlanRoot).getDataSinks(bestPlanSinks);
		}

		// finalize the plan
		OptimizedPlan plan = new PlanFinalizer().createFinalPlan(bestPlanSinks, pactPlan.getJobName(), memoryPerInstance);
		plan.setInstanceTypeName(instanceName);
		plan.setPlanConfiguration(pactPlan.getPlanConfiguration());
		
		// post pass the plan. this is the phase where the serialization and comparator code is set
		postPasser.postPass(plan);
		
		return plan;
	}

	/**
	 * This function performs only the first step to the compilation process - the creation of the optimizer
	 * representation of the plan. No estimations or enumerations of alternatives are done here.
	 * 
	 * @param pactPlan
	 *        The plan to generate the optimizer representation for.
	 * @return The optimizer representation of the plan.
	 */
	public static OptimizedPlan createPreOptimizedPlan(Plan pactPlan)
	{
//		GraphCreatingVisitor graphCreator = new GraphCreatingVisitor(null, -1, 1, false);
//		pactPlan.accept(graphCreator);
//		OptimizedPlan optPlan = new OptimizedPlan(graphCreator.sources, graphCreator.sinks, graphCreator.con2node.values(),
//				pactPlan.getJobName());
//		optPlan.setPlanConfiguration(pactPlan.getPlanConfiguration());
//		return optPlan;
		return null;
	}
	
	// ------------------------------------------------------------------------
	//                 Visitors for Compilation Traversals
	// ------------------------------------------------------------------------
	
	/**
	 * This utility class performs the translation from the user specified PACT job to the optimizer plan.
	 * It works as a visitor that walks the user's job in a depth-first fashion. During the descend, it creates
	 * an optimizer node for each pact, respectively data source or -sink. During the ascend, it connects
	 * the nodes to the full graph.
	 * <p>
	 * This translator relies on the <code>setInputs</code> method in the nodes. As that method implements the size
	 * estimation and the awareness for optimizer hints, the sizes will be properly estimated and the translated plan
	 * already respects all optimizer hints.
	 */
	private static final class GraphCreatingVisitor implements Visitor<Contract>
	{
		private final Map<Contract, OptimizerNode> con2node; // map from the contract objects to their
																// corresponding optimizer nodes

		private final List<DataSourceNode> sources; // all data source nodes in the optimizer plan

		private final List<DataSinkNode> sinks; // all data sink nodes in the optimizer plan

		private final DataStatistics statistics; // used to access basic file statistics

		private final int maxMachines; // the maximum number of machines to use

		private final int defaultParallelism; // the default degree of parallelism

		private int id; // the incrementing id for the nodes.
		
		private int numMemoryConsumers;

		private final boolean computeEstimates; // flag indicating whether to compute additional info


		GraphCreatingVisitor(DataStatistics statistics, int maxMachines, int defaultParallelism, boolean computeEstimates)
		{
			this.con2node = new HashMap<Contract, OptimizerNode>();
			this.sources = new ArrayList<DataSourceNode>(4);
			this.sinks = new ArrayList<DataSinkNode>(2);
			this.statistics = statistics;
			this.maxMachines = maxMachines;
			this.defaultParallelism = defaultParallelism;
			this.id = 1;
			this.computeEstimates = computeEstimates;
		}

		/*
		 * (non-Javadoc)
		 * @see
		 * eu.stratosphere.pact.common.plan.Visitor#preVisit(eu.stratosphere.pact.common.plan.Visitable)
		 */
		@Override
		public boolean preVisit(Contract c) {
			// check if we have been here before
			if (this.con2node.containsKey(c)) {
				return false;
			}

			final OptimizerNode n;

			// create a node for the pact (or sink or source) if we have not been here before
			if (c instanceof GenericDataSink) {
				DataSinkNode dsn = new DataSinkNode((GenericDataSink) c);
				this.sinks.add(dsn);
				n = dsn;
			} else if (c instanceof GenericDataSource) {
				DataSourceNode dsn = new DataSourceNode((GenericDataSource<?>) c);
				this.sources.add(dsn);
				n = dsn;
			} else if (c instanceof GenericMapContract) {
				n = new MapNode((GenericMapContract<?>) c);
			} else if (c instanceof GenericReduceContract) {
				n = new ReduceNode((GenericReduceContract<?>) c);
			} else if (c instanceof GenericMatchContract) {
				n = new MatchNode((GenericMatchContract<?>) c);
			} else if (c instanceof GenericCoGroupContract) {
				n = new CoGroupNode((GenericCoGroupContract<?>) c);
			} else if (c instanceof GenericCrossContract) {
				n = new CrossNode((GenericCrossContract<?>) c);
			} else {
				throw new IllegalArgumentException("Unknown contract type.");
			}

			this.con2node.put(c, n);
			
			// record the potential memory consumption
			this.numMemoryConsumers += n.isMemoryConsumer() ? 1 : 0;

			// set the degree of parallelism
			int par = c.getDegreeOfParallelism();
			par = par >= 1 ? par : this.defaultParallelism;

			// set the parallelism only if it has not been set before
			if (n.getDegreeOfParallelism() < 1) {
				n.setDegreeOfParallelism(par);
			}

			// check if we need to set the instance sharing accordingly such that
			// the maximum number of machines is not exceeded
			int tasksPerInstance = 1;
			if (this.maxMachines > 0) {
				int p = n.getDegreeOfParallelism();
				tasksPerInstance = (p / this.maxMachines) + (p % this.maxMachines == 0 ? 0 : 1);
			}

			// we group together n tasks per machine, depending on config and the above computed
			// value required to obey the maximum number of machines
			n.setSubtasksPerInstance(tasksPerInstance);
			return true;
		}

		/*
		 * (non-Javadoc)
		 * @see
		 * eu.stratosphere.pact.common.plan.Visitor#postVisit(eu.stratosphere.pact.common.plan.Visitable)
		 */
		@Override
		public void postVisit(Contract c) {
			OptimizerNode n = this.con2node.get(c);

			// check if we have been here before
			if (n.getId() > 0) {
				return;
			}
			n.SetId(this.id);

			// first connect to the predecessors
			if(!(c instanceof GenericDataSource))
				n.setInputs(this.con2node);

			//read id again as it might have been incremented for newly created union nodes
			this.id = n.getId() + 1;
			
			// now compute the output estimates
			if (this.computeEstimates) {
				n.computeOutputEstimates(this.statistics);
			}
		}

		int getId() {
			return this.id;
		}
		
		int getMemoryConsumerCount() {
			return this.numMemoryConsumers;
		}
	};
	
	/**
	 * Simple visitor that sets the minimal guaranteed memory per task based on the amount of available memory,
	 * the number of memory consumers, and on the task's degree of parallelism.
	 */
	private static final class MemoryDistributer implements Visitor<OptimizerNode>
	{
		private final long memoryPerTaskPerInstance;
		
		MemoryDistributer(long memoryPerTaskPerInstance) {
			this.memoryPerTaskPerInstance = memoryPerTaskPerInstance;
		}

		/* (non-Javadoc)
		 * @see eu.stratosphere.pact.common.plan.Visitor#preVisit(eu.stratosphere.pact.common.plan.Visitable)
		 */
		@Override
		public boolean preVisit(OptimizerNode visitable) {
			if (visitable.getMinimalMemoryPerSubTask() == -1) {
				final long mem = visitable.isMemoryConsumer() ? 
					this.memoryPerTaskPerInstance / visitable.getSubtasksPerInstance() : 0;
				visitable.setMinimalMemoryPerSubTask(mem);
				return true;
			} else {
				return false;
			}
		}

		/* (non-Javadoc)
		 * @see eu.stratosphere.pact.common.plan.Visitor#postVisit(eu.stratosphere.pact.common.plan.Visitable)
		 */
		@Override
		public void postVisit(OptimizerNode visitable) {}
	}
	
	/**
	 * Visitor that computes the interesting properties for each node in the plan. On its recursive
	 * depth-first descend, it propagates all interesting properties top-down.
	 */
	private static final class InterestingPropertyVisitor implements Visitor<OptimizerNode>
	{
		private CostEstimator estimator; // the cost estimator for maximal costs of an interesting property

		/**
		 * Creates a new visitor that computes the interesting properties for all nodes in the plan.
		 * It uses the given cost estimator used to compute the maximal costs for an interesting property.
		 * 
		 * @param estimator
		 *        The cost estimator to estimate the maximal costs for interesting properties.
		 */
		InterestingPropertyVisitor(CostEstimator estimator) {
			this.estimator = estimator;
		}
		
		/*
		 * (non-Javadoc)
		 * @see
		 * eu.stratosphere.pact.common.plan.Visitor#preVisit(eu.stratosphere.pact.common.plan.Visitable)
		 */
		@Override
		public boolean preVisit(OptimizerNode node) {
			// The interesting properties must be computed on the descend. In case a node has multiple outputs,
			// that computation must happen during the last descend.

			if (node.haveAllOutputConnectionInterestingProperties() && node.getInterestingProperties() == null) {
				node.computeUnionOfInterestingPropertiesFromSuccessors();
				node.computeInterestingPropertiesForInputs(this.estimator);
				return true;
			} else {
				return false;
			}
		}

		/* (non-Javadoc)
		 * @see eu.stratosphere.pact.common.plan.Visitor#postVisit(eu.stratosphere.pact.common.plan.Visitable)
		 */
		@Override
		public void postVisit(OptimizerNode visitable) {}
	}

	/**
	 * Visitor that computes the interesting properties for each node in the plan. On its recursive
	 * depth-first descend, it propagates all interesting properties top-down. On its re-ascend,
	 * it computes auxiliary maps that are needed to support plans that are not a minimally connected
	 * DAG (Such plans are not trees, but at least one node feeds its output into more than one other
	 * node).
	 */
	private static final class BranchesVisitor implements Visitor<OptimizerNode>
	{
		/*
		 * (non-Javadoc)
		 * @see
		 * eu.stratosphere.pact.common.plan.Visitor#preVisit(eu.stratosphere.pact.common.plan.Visitable)
		 */
		@Override
		public boolean preVisit(OptimizerNode node) {
			// make sure we descend in any case (even if it causes redundant descends), because the branch propagation
			// during the post visit needs to happen during the first re-ascend
			return true;
		}

		/*
		 * (non-Javadoc)
		 * @see
		 * eu.stratosphere.pact.common.plan.Visitor#postVisit(eu.stratosphere.pact.common.plan.Visitable)
		 */
		@Override
		public void postVisit(OptimizerNode node) {
			node.computeUnclosedBranchStack();
		}
	};
	
	/**
	 * Utility class that traverses a plan to collect all nodes and add them to the OptimizedPlan.
	 * Besides collecting all nodes, this traversal assigns the memory to the nodes.
	 */
	private static final class PlanFinalizer implements Visitor<PlanNode>
	{
		private final Set<PlanNode> allNodes; // a set of all nodes in the optimizer plan

		private final List<SourcePlanNode> sources; // all data source nodes in the optimizer plan

		private final List<SinkPlanNode> sinks; // all data sink nodes in the optimizer plan

		private long memoryPerInstance; // the amount of memory per instance
		
		private int memoryConsumerWeights; // a counter of all memory consumers

		/**
		 * Creates a new plan finalizer.
		 */
		private PlanFinalizer() {
			this.allNodes = new HashSet<PlanNode>();
			this.sources = new ArrayList<SourcePlanNode>();
			this.sinks = new ArrayList<SinkPlanNode>();
		}

		private OptimizedPlan createFinalPlan(List<SinkPlanNode> sinks, String jobName, long memPerInstance)
		{
			if (LOG.isDebugEnabled())
				LOG.debug("Available memory per instance: " + memoryPerInstance);
			
			this.memoryPerInstance = memPerInstance;
			this.memoryConsumerWeights = 0;
			
			// traverse the graph
			for (SinkPlanNode node : sinks) {
				node.accept(this);
			}

			// assign the memory to each node
			if (this.memoryConsumerWeights > 0) {
				final long memoryPerSubTask = this.memoryPerInstance / this.memoryConsumerWeights;
				
				if (LOG.isDebugEnabled())
					LOG.debug("Memory per consumer: " + memoryPerSubTask);
				
				for (PlanNode node : this.allNodes) {
					final int consumerWeight = node.getMemoryConsumerWeight();
					if (consumerWeight > 0) {
						node.setMemoryPerSubTask(memoryPerSubTask * consumerWeight);
						if (LOG.isDebugEnabled()) {
							final long mib = (memoryPerSubTask * consumerWeight) >> 20;
							LOG.debug("Assigned " + mib + " MiBytes memory to each subtask of " + 
								node.getPactContract().getName() + " (" + mib * node.getDegreeOfParallelism() +
								" MiBytes total."); 
						}
					}
				}
			}
			return new OptimizedPlan(this.sources, this.sinks, this.allNodes, jobName);
		}

		/*
		 * (non-Javadoc)
		 * @see
		 * eu.stratosphere.pact.common.plan.Visitor#preVisit(eu.stratosphere.pact.common.plan.Visitable)
		 */
		@Override
		public boolean preVisit(PlanNode visitable) {
			// if we come here again, prevent a further descend
			if (!this.allNodes.add(visitable)) {
				return false;
			}
			
			if (visitable instanceof SinkPlanNode) {
				this.sinks.add((SinkPlanNode) visitable);
			} else if (visitable instanceof SourcePlanNode) {
				this.sources.add((SourcePlanNode) visitable);
			}
			
			for (Iterator<Channel> iter = visitable.getInputs(); iter.hasNext();) {
				final Channel conn = iter.next();
				conn.setTarget(visitable);
				conn.getSource().addOutgoingChannel(conn);
			}

			// count the memory consumption
			this.memoryConsumerWeights += visitable.getMemoryConsumerWeight();
			return true;
		}

		/*
		 * (non-Javadoc)
		 * @see
		 * eu.stratosphere.pact.common.plan.Visitor#postVisit(eu.stratosphere.pact.common.plan.Visitable)
		 */
		@Override
		public void postVisit(PlanNode visitable) {}
	}

	// ------------------------------------------------------------------------
	// Miscellaneous
	// ------------------------------------------------------------------------

	private InstanceTypeDescription getInstanceTypeInfo() {
		if (LOG.isDebugEnabled()) {
			LOG.debug("Connecting compiler to JobManager to dertermine instance information.");
		}
		
		// create the connection in a separate thread, such that this thread
		// can abort, if an unsuccessful connection occurs.
		Map<InstanceType, InstanceTypeDescription> instances = null;
		
		JobManagerConnector jmc = new JobManagerConnector(this.jobManagerAddress);
		Thread connectorThread = new Thread(jmc, "Compiler - JobManager connector.");
		connectorThread.setDaemon(true);
		connectorThread.start();

		// connect and get the result
		try {
			jmc.waitForCompletion();
			instances = jmc.instances;
			if (instances == null) {
				throw new NullPointerException("Returned instance map is <null>");
			}
		}
		catch (Throwable t) {
			throw new CompilerException("Available instances could not be determined from job manager: " + 
				t.getMessage(), t);
		}

		// determine which type to run on
		return getType(instances);
	}
	
	/**
	 * This utility method picks the instance type to be used for scheduling PACT processor
	 * instances.
	 * <p>
	 * 
	 * @param types The available types.
	 * @return The type to be used for scheduling.
	 * 
	 * @throws CompilerException
	 * @throws IllegalArgumentException
	 */
	private InstanceTypeDescription getType(Map<InstanceType, InstanceTypeDescription> types)
	throws CompilerException
	{
		if (types == null || types.size() < 1) {
			throw new IllegalArgumentException("No instance type found.");
		}
		
		InstanceTypeDescription retValue = null;
		long totalMemory = 0;
		int numInstances = 0;
		
		final Iterator<InstanceTypeDescription> it = types.values().iterator();
		while(it.hasNext())
		{
			final InstanceTypeDescription descr = it.next();
			
			// skip instances for which no hardware description is available
			// this means typically that no 
			if (descr.getHardwareDescription() == null || descr.getInstanceType() == null) {
				continue;
			}
			
			final int curInstances = descr.getMaximumNumberOfAvailableInstances();
			final long curMemory = curInstances * descr.getHardwareDescription().getSizeOfFreeMemory();
			
			// get, if first, or if it has more instances and not less memory, or if it has significantly more memory
			// and the same number of cores still
			if ( (retValue == null) ||
				 (curInstances > numInstances && (int) (curMemory * 1.2f) > totalMemory) ||
				 (curInstances * retValue.getInstanceType().getNumberOfCores() >= numInstances && 
							(int) (curMemory * 1.5f) > totalMemory)
				)
			{
				retValue = descr;
				numInstances = curInstances;
				totalMemory = curMemory;
			}
		}
		
		if (retValue == null) {
			throw new CompilerException("No instance currently registered at the job-manager. Retry later.\n" +
				"If the system has recently started, it may take a few seconds until the instances register.");
		}
		
		return retValue;
	}
	
	/**
	 * Utility class for an asynchronous connection to the job manager to determine the available instances.
	 */
	private static final class JobManagerConnector implements Runnable
	{
		private static final long MAX_MILLIS_TO_WAIT = 10000;
		
		private final InetSocketAddress jobManagerAddress;
		
		private final Object lock = new Object();
		
		private volatile Map<InstanceType, InstanceTypeDescription> instances;
		
		private volatile Throwable error;
		
		
		private JobManagerConnector(InetSocketAddress jobManagerAddress)
		{
			this.jobManagerAddress = jobManagerAddress;
		}
		
		
		public void waitForCompletion() throws Throwable
		{
			long start = System.currentTimeMillis();
			long remaining = MAX_MILLIS_TO_WAIT;
			
			if (this.error != null) {
				throw this.error;
			}
			if (this.instances != null) {
				return;
			}
			
			do {
				try {
					synchronized (this.lock) {
						this.lock.wait(remaining);
					}
				} catch (InterruptedException iex) {}
			}
			while (this.error == null && this.instances == null &&
					(remaining = MAX_MILLIS_TO_WAIT + start - System.currentTimeMillis()) > 0);
			
			if (this.error != null) {
				throw this.error;
			}
			if (this.instances != null) {
				return;
			}
			
			// try to forcefully shut this thread down
			throw new IOException("Connection timed out.");
		}
		
		/* (non-Javadoc)
		 * @see java.lang.Runnable#run()
		 */
		@Override
		public void run()
		{
			ExtendedManagementProtocol jobManagerConnection = null;

			try {
				jobManagerConnection = RPC.getProxy(ExtendedManagementProtocol.class,
					this.jobManagerAddress, NetUtils.getSocketFactory());

				this.instances = jobManagerConnection.getMapOfAvailableInstanceTypes();
				if (this.instances == null) {
					throw new IOException("Returned instance map was <null>");
				}
			}
			catch (Throwable t) {
				this.error = t;
			}
			finally {
				// first of all, signal completion
				synchronized (this.lock) {
					this.lock.notifyAll();
				}
				
				if (jobManagerConnection != null) {
					try {
						RPC.stopProxy(jobManagerConnection);
					} catch (Throwable t) {
						LOG.error("Could not cleanly shut down connection from compiler to job manager,", t);
					}
				}
				jobManagerConnection = null;
			}
		}
	}
}
