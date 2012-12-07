/***********************************************************************************************************************
 *
 * Copyright (C) 2012 by the Stratosphere project (http://stratosphere.eu)
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

package eu.stratosphere.pact.compiler.dataproperties;

import java.util.List;

import eu.stratosphere.pact.common.util.FieldList;
import eu.stratosphere.pact.common.util.FieldSet;
import eu.stratosphere.pact.compiler.plan.SingleInputNode;
import eu.stratosphere.pact.compiler.plan.candidate.Channel;
import eu.stratosphere.pact.compiler.plan.candidate.SingleInputPlanNode;

/**
 * 
 */
public abstract class DriverPropertiesSingle implements DriverProperties
{
	protected final FieldSet keys;
	protected  final FieldList keyList;
	
	private final List<RequestedGlobalProperties> globalProps;
	private final List<RequestedLocalProperties> localProps;
	
	protected DriverPropertiesSingle() {
		this(null);
	}
	
	protected DriverPropertiesSingle(FieldSet keys) {
		this.keys = keys;
		this.keyList = keys == null ? null : keys.toFieldList();
		this.globalProps = createPossibleGlobalProperties();
		this.localProps = createPossibleLocalProperties();
	}
	
	public List<RequestedGlobalProperties> getPossibleGlobalProperties() {
		return this.globalProps;
	}
	
	public List<RequestedLocalProperties> getPossibleLocalProperties() {
		return this.localProps;
	}
	
	protected abstract List<RequestedGlobalProperties> createPossibleGlobalProperties();
	
	protected abstract List<RequestedLocalProperties> createPossibleLocalProperties();
	
	public abstract SingleInputPlanNode instantiate(Channel in, SingleInputNode node);
	
	
}
