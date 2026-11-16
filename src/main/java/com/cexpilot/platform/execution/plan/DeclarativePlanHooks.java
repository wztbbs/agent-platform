package com.cexpilot.platform.execution.plan;

import com.cexpilot.platform.tool.execution.PlanExecutionStatus;
import com.cexpilot.platform.tool.execution.PreparedPlanExecutor;

import com.cexpilot.platform.agent.AgentRunContext;
import com.cexpilot.platform.agent.ConfiguredAgentDefinition;
import com.cexpilot.platform.execution.ExecutionFailure;
import com.cexpilot.platform.policy.approval.*;
import com.cexpilot.platform.llm.PlanningJson;
import com.cexpilot.platform.llm.ModelOutputException;
import com.cexpilot.platform.llm.ChatResponse;
import com.cexpilot.platform.llm.LlmCallService;

import com.cexpilot.platform.run.RunLeaseLostException;
import com.cexpilot.platform.run.RunSuspended;
import com.cexpilot.platform.tool.calculation.CalculationTool;
import com.cexpilot.platform.execution.dag.DagExecutor;
import com.cexpilot.platform.execution.dag.DagPlan;
import com.cexpilot.platform.execution.dag.PlanNode;
import com.cexpilot.platform.execution.dag.ReferenceResolver;
import com.cexpilot.platform.context.evidence.ToolEvidenceWriter;
import com.cexpilot.platform.llm.ChatMessage;
import com.cexpilot.platform.llm.LlmUsage;
import com.cexpilot.platform.context.prompt.PromptStore;
import com.cexpilot.platform.tool.ToolArguments;
import com.cexpilot.platform.tool.ToolRegistry;
import com.cexpilot.platform.tool.ToolResult;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.execution.QueryBudget;
import com.cexpilot.platform.execution.ExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * 声明式 Agent（engine: plan）的引擎内置回调：LLM 输出工具/计算节点计划，
 * 校验结构与引用后交 DagExecutor 拓扑分层执行，再基于成功节点生成回答。
 */
final class DeclarativePlanHooks implements PlanHooks {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    /**
     * 规划提示词/回答提示词模板名。
     */
    static final String PLAN_PROMPT = "plan_agent", ANSWER_PROMPT = "plan_agent_answer";
    private final ConfiguredAgentDefinition definition;
    private final ToolRegistry tools;
    private final PromptStore prompts;
    private final LlmCallService llm;
    private final ToolEvidenceWriter evidence;
    private final DagExecutor dagExecutor;
    private final PreparedPlanService preparedPlans;
    private final PreparedPlanExecutor preparedExecutor;

    DeclarativePlanHooks(ConfiguredAgentDefinition definition, ToolRegistry tools, PromptStore prompts,
                         LlmCallService llm, ToolEvidenceWriter evidence, DagExecutor dagExecutor, PreparedPlanService preparedPlans, PreparedPlanExecutor preparedExecutor) {
        this.definition = definition;
        this.tools = tools;
        this.prompts = prompts;
        this.llm = llm;
        this.evidence = evidence;
        this.dagExecutor = dagExecutor;
        this.preparedPlans = preparedPlans;
        this.preparedExecutor = preparedExecutor;
    }

    @Override
    public String stage() {
        return "agent_plan";
    }

    @Override
    public String planningPrompt() {
        return definition.toolApprovalMode() == ToolApprovalMode.PREPARED_PLAN ? "plan_base_prepare" : PLAN_PROMPT;
    }

    @Override
    public int maxAttempts() {
        return 2;
    }

    @Override
    public Map<String, String> planningVars() {
        var catalog = JSON.createArrayNode();
        definition.allowedTools().stream().sorted().forEach(name -> {
            var item = catalog.addObject().put("name", name).put("description", tools.spec(name).description());
            item.set("control", JSON.valueToTree(tools.definition(name).control()));
            item.set("input_schema", tools.spec(name).inputSchema());
            item.set("output_schema", tools.outputSchema(name));
        });
        return Map.of("agent_prompt", prompts.render(definition.prompt(), Map.of()), "tools", catalog.toString());
    }

    @Override
    public ObjectNode planningInput(AgentRunContext run) {
        return JSON.createObjectNode().put("question", run.question()).put("recent_dialogue", run.history() == null ? "" : run.history())
                .put("request_time", run.request().requestTime().toString())
                .put("timezone", (run.request().userZone() == null ? java.time.ZoneOffset.ofHours(8) : run.request().userZone()).getId());
    }

    @Override
    public PlanVerdict validate(JsonNode planOutput, AgentRunContext run, List<LlmUsage> usages) {
        try {
            var plan = compile(planOutput, definition);
            if (definition.toolApprovalMode() == ToolApprovalMode.PREPARED_PLAN)
                preparedPlans.validateProposal(proposal(plan, run));
            var compiled = plan.toJson();
            return PlanVerdict.compiled(compiled);
        } catch (IllegalArgumentException invalid) {
            return PlanVerdict.repair(invalid.getMessage());
        }
    }

    @Override
    public String repairMessage(String validationError) {
        return "计划无效：" + validationError + "。只返回合法的计划 JSON。";
    }

    @Override
    public ExecutionResult complete(PlanCompletion completion) {
        var execution = prepareExecution(completion);
        try {
            if (execution.run.progress() != null) execution.run.progress().accept("正在按计划获取数据…");
            var approval = prepareAndCheckApproval(execution);
            if (approval.stopped()) return buildApprovalStoppedResult(execution, approval);

            var results = executePlan(execution, approval);
            collectExecutionResults(execution, results);
            execution.answer = generateAnswer(execution);
        } catch (QueryBudget.Exhausted exhausted) {
            execution.stop = exhausted.reason;
            execution.partial = true;
            execution.answer = "本次执行达到预算或时间限制，尚未完整完成；已取得的证据保留在执行记录中。";
        } catch (RunSuspended | RunLeaseLostException | ExecutionFailure control) {
            throw control;
        } catch (RuntimeException | JsonProcessingException failure) {
            throw new ExecutionFailure(failure,
                    new com.cexpilot.platform.execution.TurnCounters(execution.plannerCalls, execution.answerCalls, 0, 0, execution.httpCalls, 0, execution.saved)
                            .withLlmUsages(execution.usages).withToolCalls(execution.facts.size()), null, execution.facts);
        }
        return buildExecutionResult(execution);
    }

    /** 还原规划产物，初始化本次执行的预算、模型用量、计数及结果容器。 */
    private ExecutionState prepareExecution(PlanCompletion completion) {
        return new ExecutionState(completion);
    }

    /** 方案模式下冻结调用并检查审批；待审批时暂停，其他模式保留工具网关的逐调用确认。 */
    private ApprovalDecision prepareAndCheckApproval(ExecutionState execution) {
        if (definition.toolApprovalMode() != ToolApprovalMode.PREPARED_PLAN) {
            return new ApprovalDecision(null, null);
        }
        var proposal = proposal(execution.plan, execution.run);
        var frozen = preparedPlans.prepare("declarative_plan", proposal);
        var status = preparedPlans.approval(frozen);
        if (status == PlanApprovalStatus.PENDING) {
            throw RunSuspended.approval(frozen.id());
        }
        return new ApprovalDecision(frozen.id(), status);
    }

    /** 审批已拒绝、过期或被替换时直接结束，不执行工具、不调用回答模型。 */
    private ExecutionResult buildApprovalStoppedResult(ExecutionState execution, ApprovalDecision approval) {
        execution.answer = "方案已拒绝、过期或被替换，未继续执行。";
        execution.stop = approval.status().name();
        execution.partial = true;
        execution.failed = true;
        return buildExecutionResult(execution);
    }

    /** 按审批模式选择执行器，统一提供按节点 ID 读取真实结果的入口。 */
    private Function<String, ToolResult> executePlan(ExecutionState execution, ApprovalDecision approval) {
        if (approval.prepared()) return executePreparedPlan(execution, approval.planId());
        return executeDagPlan(execution);
    }

    /** 执行已批准的整份方案；失败或部分完成时保留真实执行状态。 */
    private Function<String, ToolResult> executePreparedPlan(ExecutionState execution, String planId) {
        var outcome = preparedExecutor.execute(planId, execution.run.sink());
        execution.failed = outcome.status() != PlanExecutionStatus.SUCCEEDED;
        execution.partial |= execution.failed;
        if (execution.failed) execution.stop = outcome.status().name();
        return outcome.results()::get;
    }

    /** 执行原 DAG 路径；工具调用仍经过统一网关，保留逐工具确认规则。 */
    private Function<String, ToolResult> executeDagPlan(ExecutionState execution) {
        var run = execution.run;
        var identity = new DagExecutor.NodeIdentity(run.session() == null ? null : run.session().conversationId(),
                definition.agent().id(), run.visitor(), definition.allowedTools());
        var outcome = dagExecutor.executeIdentified(execution.plan, run.traceId(), run.sink(), run.request(), execution.budget, identity);
        return outcome.context()::get;
    }

    /** 按计划顺序汇总成功／失败结果、保存可用证据，并累计调用次数及部分完成标记。 */
    private void collectExecutionResults(ExecutionState execution, Function<String, ToolResult> results)
            throws JsonProcessingException {
        var run = execution.run;
        for (PlanNode node : execution.plan.nodes()) {
            ToolResult result = results.apply(node.id());
            if (result == null) continue;
            if (tools.isHttp(node.tool())) execution.httpCalls++;
            var fact = (ObjectNode) JSON.readTree(result.toMessageContent());
            fact.put("ref", node.id()).put("tool", node.tool());
            if (result.ok()) {
                execution.okNodeIds.add(node.id());
                if (run.session() != null && evidence != null && tools.isHttp(node.tool())) {
                    var stored = com.cexpilot.platform.context.evidence.ToolEvidenceCheckpoint.saveOptional(evidence, run, definition.agent().id(), node.tool(), node.id(), result);
                    execution.partial |= stored.unavailable();
                    if (stored.unavailable()) fact.put("persistence", "UNAVAILABLE");
                    stored.factId().ifPresent(id -> {
                        execution.saved.add(id);
                        fact.put("fact_id", id);
                    });
                }
            } else execution.partial = true;
            execution.facts.add(fact);
        }
    }

    /** 根据执行结果生成回答；无成功证据或回答校验失败时按原规则降级，并保留已发生的模型用量。 */
    private String generateAnswer(ExecutionState execution) {
        if (execution.okNodeIds.isEmpty()) {
            execution.partial = true;
            return "本次未取得可用证据，无法给出可靠结果。";
        }
        var run = execution.run;
        var input = JSON.createObjectNode().put("question", run.question()).put("stop_reason", execution.stop).put("partial", execution.partial);
        input.set("facts", execution.facts);
        int before = execution.usages.size();
        try {
            var response = llm.call("agent_answer", ANSWER_PROMPT, List.of(ChatMessage.system(prompts.render(ANSWER_PROMPT, Map.of())), ChatMessage.user(input.toString())),
                    run.traceId(), run.sink(), execution.usages);
            return parseAnswer(response, execution.okNodeIds);
        } catch (ModelOutputException invalid) {
            run.sink().record(new TraceEvent(run.traceId(), "AGENT_REJECTED", definition.agent().id(), null, null, null, null, null, null, null, invalid.getMessage()));
            execution.stop = "ANSWER_INVALID";
            execution.partial = true;
            return "本次未能生成可靠回答，已保留执行记录。";
        } finally {
            execution.answerCalls = execution.usages.size() - before;
        }
    }

    /** 汇总最终状态、回答、事实与调用计数，复用统一的完成事件及结果组装。 */
    private ExecutionResult buildExecutionResult(ExecutionState execution) {
        return result(execution.run, execution.answer, execution.facts, execution.usages, execution.plannerCalls,
                execution.answerCalls, execution.facts.size(), execution.httpCalls, execution.saved,
                execution.stop, execution.partial, execution.failed);
    }

    /** planId/status 为空表示逐调用模式；非空状态仅表示整份方案的真实审批结果。 */
    private record ApprovalDecision(String planId, PlanApprovalStatus status) {
        boolean prepared() { return planId != null; }
        boolean stopped() { return status != null && status != PlanApprovalStatus.APPROVED; }
    }

    /** 仅属于本次 complete 调用的临时状态；恢复所需数据仍由 RunScope 和方案／工具记录持久化。 */
    private static final class ExecutionState {
        final AgentRunContext run;
        final DagPlan plan;
        final QueryBudget budget;
        final List<LlmUsage> usages;
        final int plannerCalls;
        final ArrayNode facts = JSON.createArrayNode();
        final List<String> saved = new ArrayList<>();
        final Set<String> okNodeIds = new HashSet<>();
        int answerCalls, httpCalls;
        boolean partial, failed;
        String stop = "MODEL_FINISH";
        String answer;

        ExecutionState(PlanCompletion completion) {
            run = completion.run();
            usages = new ArrayList<>(completion.usages());
            plannerCalls = completion.plannerCalls();
            plan = DagPlan.fromJson((JsonNode) completion.compiled());
            budget = QueryBudget.current();
        }
    }

    private PreparedPlan proposal(DagPlan plan, AgentRunContext run) {
        String summary = run.question().length() > 1000 ? run.question().substring(0, 1000) : run.question();
        return new PreparedPlan(summary, plan.nodes().stream().map(node -> new PreparedToolCall(node.id(), node.tool(), node.args(), node.dependsOn())).toList(), 86400);
    }

    private static String parseAnswer(ChatResponse response, Set<String> okNodeIds) {
        if (response.hasToolCalls()) throw new ModelOutputException("回答不能调用工具");
        var output = PlanningJson.parse(response.content());
        PlanningJson.object(output, Set.of("answer", "evidence_refs"), "Agent 回答");
        var references = answerRefs(output.path("evidence_refs"), okNodeIds);
        if (references.isEmpty()) throw new ModelOutputException("回答缺少有效证据引用");
        return PlanningJson.text(output, "answer", 8000);
    }

    @Override
    public ExecutionResult exhausted(PlanCompletion completion) {
        return degraded(completion, "PLAN_INVALID", "本次未能生成有效的执行计划，已保留执行记录。");
    }

    @Override
    public ExecutionResult budgetExhausted(PlanCompletion completion, QueryBudget.Exhausted exhausted) {
        return degraded(completion, exhausted.reason, "本次执行达到预算或时间限制，尚未完整完成；已取得的证据保留在执行记录中。");
    }

    private ExecutionResult degraded(PlanCompletion completion, String stop, String answer) {
        return result(completion.run(), answer, JSON.createArrayNode(), completion.usages(),
                completion.plannerCalls(), 0, 0, 0, List.of(), stop, true, definition.toolApprovalMode() == ToolApprovalMode.PREPARED_PLAN);
    }

    private ExecutionResult result(AgentRunContext run, String answer, ArrayNode facts, List<LlmUsage> usages,
                                   int plannerCalls, int answerCalls, int toolCalls, int httpCalls, List<String> saved, String stop, boolean partial) {
        return result(run, answer, facts, usages, plannerCalls, answerCalls, toolCalls, httpCalls, saved, stop, partial, false);
    }

    private ExecutionResult result(AgentRunContext run, String answer, ArrayNode facts, List<LlmUsage> usages,
                                   int plannerCalls, int answerCalls, int toolCalls, int httpCalls, List<String> saved, String stop, boolean partial, boolean failed) {
        var payload = JSON.createObjectNode().put("status", failed ? "FAILED" : "SUCCEEDED")
                .put("agent_id", definition.agent().id()).put("execution_status", partial ? "PARTIAL" : "FINISHED").put("stop_reason", stop);
        if (QueryBudget.current() != null) { payload.set("budget", QueryBudget.current().snapshot()); }
        run.sink().record(new TraceEvent(run.traceId(), "AGENT_FINISHED", definition.agent().id(), null, payload.toString(), null, null, null, null, null, null));
        return new ExecutionResult(answer, facts, toolCalls, plannerCalls, LlmUsage.knownPromptTokens(usages), LlmUsage.knownCompletionTokens(usages),
                failed ? "FAILED" : null, null, 0, plannerCalls, answerCalls, 0, 0, httpCalls, saved).withLlmUsages(usages).withAgentRouteAndPayload(definition.agent().route(), payload);
    }

    /**
     * 把模型输出的计划 JSON 编译为 DagPlan：结构校验、工具白名单、引用语法与目标检查、
     * 规划期参数校验；节点依赖由引用推导，环检测后返回。
     */
    private DagPlan compile(JsonNode json, ConfiguredAgentDefinition definition) {
        PlanningJson.object(json, Set.of("nodes"), "执行计划");
        JsonNode nodesNode = json.path("nodes");
        if (!nodesNode.isArray() || nodesNode.isEmpty() || nodesNode.size() > 16)
            throw new IllegalArgumentException("nodes 必须为 1~16 个节点的数组");
        var ids = new LinkedHashSet<String>();
        var nodes = new ArrayList<PlanNode>();
        for (JsonNode raw : nodesNode) {
            String id = raw.path("id").isTextual() ? raw.path("id").asText() : null;
            if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_]{0,39}") || !ids.add(id))
                throw new IllegalArgumentException("节点 id 缺失、非法或重复");
            String tool = raw.path("tool").isTextual() ? raw.path("tool").asText() : null;
            if (tool == null || !definition.allowedTools().contains(tool))
                throw new IllegalArgumentException("工具未获此 Agent 授权: " + tool);
            var spec = tools.spec(tool);
            if (spec == null) throw new IllegalArgumentException("工具未注册: " + tool);
            JsonNode args = raw.path("args").isObject() ? raw.path("args") : JSON.createObjectNode();
            var syntaxErrors = ReferenceResolver.syntaxErrors(args);
            if (!syntaxErrors.isEmpty()) throw new IllegalArgumentException(String.join("; ", syntaxErrors));
            var refs = ReferenceResolver.findRefs(args);
            var dependsOn = new ArrayList<String>();
            if (definition.toolApprovalMode() == ToolApprovalMode.PREPARED_PLAN) {
                PlanningJson.object(raw, Set.of("id", "tool", "args", "depends_on"), "方案节点");
                if (raw.has("depends_on")) {
                    if (!raw.path("depends_on").isArray()) throw new IllegalArgumentException("depends_on 必须为数组");
                    for (var dependency : raw.path("depends_on")) {
                        if (!dependency.isTextual() || dependsOn.contains(dependency.asText()))
                            throw new IllegalArgumentException("依赖名称无效或重复");
                        dependsOn.add(dependency.asText());
                    }
                }
            }
            for (var ref : refs) {
                if (ref.nodeId().equals(id)) throw new IllegalArgumentException("节点不能引用自身: " + id);
                if (dependsOn.contains(ref.nodeId())) continue;
                dependsOn.add(ref.nodeId());
            }
            var executor = tools.get(tool);
            if (executor instanceof CalculationTool calculation) calculation.validateArguments(args, true);
            else {
                var errors = ToolArguments.validate(args, spec.inputSchema(), true);
                if (!errors.isEmpty())
                    throw new IllegalArgumentException(tool + " 参数非法: " + String.join("; ", errors));
            }
            nodes.add(new PlanNode(id, tool, args, dependsOn));
        }
        for (PlanNode node : nodes)
            for (String dep : node.dependsOn())
                if (!ids.contains(dep)) throw new IllegalArgumentException("引用目标不存在: " + dep);
        acyclic(nodes);
        return new DagPlan(nodes);
    }

    /**
     * Kahn 分层做环检测；有环抛 IllegalArgumentException。
     */
    private static void acyclic(List<PlanNode> nodes) {
        var indegree = new HashMap<String, Integer>();
        nodes.forEach(n -> indegree.put(n.id(), n.dependsOn().size()));
        var remaining = new ArrayList<>(nodes);
        while (!remaining.isEmpty()) {
            var ready = remaining.stream().filter(n -> indegree.get(n.id()) == 0).toList();
            if (ready.isEmpty()) throw new IllegalArgumentException("计划存在循环依赖");
            for (var node : ready) {
                remaining.remove(node);
                remaining.forEach(r -> {
                    if (r.dependsOn().contains(node.id())) indegree.merge(r.id(), -1, Integer::sum);
                });
            }
        }
    }

    /**
     * 校验回答的 evidence_refs：必须全部是实际成功的节点 id。
     */
    private static List<String> answerRefs(JsonNode node, Set<String> okNodeIds) {
        if (!node.isArray()) throw new ModelOutputException("evidence_refs 必须为数组");
        var refs = new ArrayList<String>();
        for (var ref : node) {
            if (!ref.isTextual() || !okNodeIds.contains(ref.asText()) || refs.contains(ref.asText()))
                throw new ModelOutputException("证据引用非法: " + ref.asText());
            refs.add(ref.asText());
        }
        return refs;
    }
}
