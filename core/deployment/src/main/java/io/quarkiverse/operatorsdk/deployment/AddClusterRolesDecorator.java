package io.quarkiverse.operatorsdk.deployment;

import java.util.*;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;

import io.dekorate.kubernetes.decorator.ResourceProvidingDecorator;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Deleter;
import io.javaoperatorsdk.operator.processing.dependent.Creator;
import io.javaoperatorsdk.operator.processing.dependent.Updater;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.GenericKubernetesDependentResource;
import io.quarkiverse.operatorsdk.annotations.RBACVerbs;
import io.quarkiverse.operatorsdk.runtime.DependentResourceSpecMetadata;
import io.quarkiverse.operatorsdk.runtime.QuarkusControllerConfiguration;

public class AddClusterRolesDecorator extends ResourceProvidingDecorator<KubernetesListBuilder> {

    public static final String JOSDK_CRD_VALIDATING_CLUSTER_ROLE_NAME = "josdk-crd-validating-cluster-role";
    private static final ClusterRoleBuilder CRD_VALIDATING_CLUSTER_ROLE_BUILDER = new ClusterRoleBuilder().withNewMetadata()
            .withName(JOSDK_CRD_VALIDATING_CLUSTER_ROLE_NAME).endMetadata()
            .addToRules(new PolicyRuleBuilder()
                    .addToApiGroups("apiextensions.k8s.io")
                    .addToResources("customresourcedefinitions")
                    .addToVerbs("get", "list")
                    .build());
    private static final String CR_API_VERSION = HasMetadata.getApiVersion(ClusterRole.class);
    private static final String CR_KIND = HasMetadata.getKind(ClusterRole.class);
    private final Collection<QuarkusControllerConfiguration<?>> configs;

    private final boolean validateCRDs;

    public AddClusterRolesDecorator(Collection<QuarkusControllerConfiguration<?>> configs, boolean validateCRDs) {
        this.configs = configs;
        this.validateCRDs = validateCRDs;
    }

    @Override
    public void visit(KubernetesListBuilder list) {
        configs.forEach(cri -> {
            var clusterRole = createClusterRole(cri);
            list.addToItems(clusterRole);
        });

        // if we're asking to validate the CRDs, also add CRDs permissions, once
        if (validateCRDs) {
            if (!contains(list, CR_API_VERSION, CR_KIND, JOSDK_CRD_VALIDATING_CLUSTER_ROLE_NAME)) {
                list.addToItems(CRD_VALIDATING_CLUSTER_ROLE_BUILDER);
            }
        }
    }

    public static ClusterRole createClusterRole(QuarkusControllerConfiguration<?> cri) {

        Set<PolicyRule> collectedRules = new LinkedHashSet<>();
        collectedRules.add(getClusterRolePolicyRuleFromPrimaryResource(cri));
        collectedRules.addAll(getClusterRolePolicyRulesFromDependentResources(cri));
        collectedRules.addAll(cri.getAdditionalRBACRules());

        return new ClusterRoleBuilder()
                .withNewMetadata()
                .withName(getClusterRoleName(cri.getName()))
                .endMetadata()
                .addAllToRules(mergePolicyRulesOfSameGroupsAndKinds(collectedRules))
                .build();
    }

    @NotNull
    private static Set<PolicyRule> getClusterRolePolicyRulesFromDependentResources(QuarkusControllerConfiguration<?> cri) {
        Set<PolicyRule> rules = new LinkedHashSet<>();
        final Map<String, DependentResourceSpecMetadata<?, ?, ?>> dependentsMetadata = cri.getDependentsMetadata();
        dependentsMetadata.forEach((name, spec) -> {
            final var dependentResourceClass = spec.getDependentResourceClass();
            final var associatedResourceClass = spec.getDependentType();

            // only process Kubernetes dependents
            if (HasMetadata.class.isAssignableFrom(associatedResourceClass)) {
                String resourceGroup = HasMetadata.getGroup(associatedResourceClass);
                String resourcePlural = HasMetadata.getPlural(associatedResourceClass);

                // https://github.com/operator-framework/java-operator-sdk/pull/2515
                // Workaround for typeless resource, no necessary when this pull merged
                if (GenericKubernetesDependentResource.class.isAssignableFrom(dependentResourceClass)) {
                    try {
                        // Only applied class with non-parameter constructor
                        if (Arrays.stream(dependentResourceClass.getConstructors()).anyMatch(i -> i.getParameterCount() == 0)) {
                            @SuppressWarnings("rawtypes")
                            GenericKubernetesDependentResource genericKubernetesResource = (GenericKubernetesDependentResource) dependentResourceClass
                                    .getConstructor().newInstance();
                            resourceGroup = genericKubernetesResource.getGroupVersionKind().getGroup();
                            resourcePlural = "*";
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                final var dependentRule = new PolicyRuleBuilder()
                        .addToApiGroups(resourceGroup)
                        .addToResources(resourcePlural)
                        .addToVerbs(RBACVerbs.READ_VERBS);
                if (Updater.class.isAssignableFrom(dependentResourceClass)) {
                    dependentRule.addToVerbs(RBACVerbs.UPDATE_VERBS);
                }
                if (Deleter.class.isAssignableFrom(dependentResourceClass)) {
                    dependentRule.addToVerbs(RBACVerbs.DELETE);
                }
                if (Creator.class.isAssignableFrom(dependentResourceClass)) {
                    dependentRule.addToVerbs(RBACVerbs.CREATE);
                    if (!dependentRule.getVerbs().contains(RBACVerbs.PATCH)) {
                        dependentRule.addToVerbs(RBACVerbs.PATCH);
                    }
                }
                rules.add(dependentRule.build());
            }
        });
        return rules;
    }

    private static PolicyRule getClusterRolePolicyRuleFromPrimaryResource(QuarkusControllerConfiguration<?> cri) {
        final var rule = new PolicyRuleBuilder();
        final var resourceClass = cri.getResourceClass();
        final var plural = HasMetadata.getPlural(resourceClass);
        rule.addToResources(plural);

        // if the resource has a non-Void status, also add the status resource
        if (cri.isStatusPresentAndNotVoid()) {
            rule.addToResources(plural + "/status");
        }

        // add finalizers sub-resource because it's used in several contexts, even in the absence of finalizers
        // see: https://kubernetes.io/docs/reference/access-authn-authz/admission-controllers/#ownerreferencespermissionenforcement
        rule.addToResources(plural + "/finalizers");

        rule.addToApiGroups(HasMetadata.getGroup(resourceClass))
                .addToVerbs(RBACVerbs.ALL_COMMON_VERBS)
                .build();
        return rule.build();
    }

    /**
     * Remove duplicated rules with same groups and resources, from which merge all verbs
     *
     * @param collectedRules may contain duplicated rules with same groups and resources, but different verbs
     * @return no duplicated rules
     */
    @NotNull
    private static Set<PolicyRule> mergePolicyRulesOfSameGroupsAndKinds(Set<PolicyRule> collectedRules) {
        Set<PolicyRule> mergedRules = new LinkedHashSet<>();
        collectedRules.stream()
                .map(wrapEqualOfGroupsAndKinds()).forEach(i -> {
                    if (!mergedRules.add(i)) {
                        mergedRules.stream().filter(j -> Objects.equals(j, i)).findAny().ifPresent(r -> {
                            Set<String> verbs1 = new LinkedHashSet<>(r.getVerbs());
                            Set<String> verbs2 = new LinkedHashSet<>(i.getVerbs());
                            verbs1.addAll(verbs2);
                            r.setVerbs(verbs1.stream().toList());
                        });
                    }
                });
        return mergedRules;
    }

    @NotNull
    private static Function<PolicyRule, PolicyRule> wrapEqualOfGroupsAndKinds() {
        return i -> new PolicyRule(i.getApiGroups(), i.getNonResourceURLs(), i.getResourceNames(), i.getResources(),
                i.getVerbs()) {
            @Override
            public boolean equals(Object o) {
                if (o == null)
                    return false;
                if (o instanceof PolicyRule) {
                    if (Objects.equals(
                            this.getApiGroups().stream().sorted().toList(),
                            ((PolicyRule) o).getApiGroups().stream().sorted().toList())) {
                        return Objects.equals(
                                getResources().stream().sorted().toList(),
                                ((PolicyRule) o).getResources().stream().sorted().toList());
                    }
                }
                return false;
            }

            @Override
            public int hashCode() {
                // equals method called only with same hashCode
                return 0;
            }
        };
    }

    public static String getClusterRoleName(String controller) {
        return controller + "-cluster-role";
    }
}
