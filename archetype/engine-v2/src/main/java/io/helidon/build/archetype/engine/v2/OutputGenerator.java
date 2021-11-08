/*
 * Copyright (c) 2021 Oracle and/or its affiliates.
 *
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

package io.helidon.build.archetype.engine.v2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import io.helidon.build.archetype.engine.v2.archive.Archetype;
import io.helidon.build.archetype.engine.v2.archive.ZipArchetype;
import io.helidon.build.archetype.engine.v2.descriptor.Replacement;
import io.helidon.build.archetype.engine.v2.interpreter.ASTNode;
import io.helidon.build.archetype.engine.v2.interpreter.ContextBooleanAST;
import io.helidon.build.archetype.engine.v2.interpreter.ContextNodeAST;
import io.helidon.build.archetype.engine.v2.interpreter.ContextTextAST;
import io.helidon.build.archetype.engine.v2.interpreter.FileSetAST;
import io.helidon.build.archetype.engine.v2.interpreter.FileSetsAST;
import io.helidon.build.archetype.engine.v2.interpreter.Flow;
import io.helidon.build.archetype.engine.v2.interpreter.ModelAST;
import io.helidon.build.archetype.engine.v2.interpreter.OutputAST;
import io.helidon.build.archetype.engine.v2.interpreter.TemplateAST;
import io.helidon.build.archetype.engine.v2.interpreter.TemplatesAST;
import io.helidon.build.archetype.engine.v2.interpreter.TransformationAST;
import io.helidon.build.archetype.engine.v2.template.TemplateModel;
import io.helidon.build.common.PathFilters;
import io.helidon.build.common.PropertyEvaluator;

/**
 * Generate Output files from interpreter.
 */
public class OutputGenerator {

    private final TemplateModel model;
    private final Archetype archetype;
    private final Map<String, String> properties;
    private final List<OutputAST> nodes;
    private final List<TransformationAST> transformations;
    private final List<TemplateAST> template;
    private final List<TemplatesAST> templates;
    private final List<FileSetAST> file;
    private final List<FileSetsAST> files;

    /**
     * OutputGenerator constructor.
     *
     * @param result Flow.Result from interpreter
     */
    OutputGenerator(Flow.Result result) {
        Objects.requireNonNull(result, "Flow result is null");

        this.nodes = getOutputNodes(result.outputs());
        this.model = createUniqueModel();
        this.archetype = result.archetype();
        this.properties = parseContextProperties(result.context());

        this.transformations = nodes.stream()
                .flatMap(output -> output.children().stream())
                .filter(o -> o instanceof TransformationAST)
                .map(t -> (TransformationAST) t)
                .collect(Collectors.toList());

        this.template = nodes.stream()
                .flatMap(output -> output.children().stream())
                .filter(o -> o instanceof TemplateAST)
                .map(t -> (TemplateAST) t)
                .filter(t -> t.engine().equals("mustache"))
                .collect(Collectors.toList());

        this.templates = nodes.stream()
                .flatMap(output -> output.children().stream())
                .filter(o -> o instanceof TemplatesAST)
                .map(t -> (TemplatesAST) t)
                .filter(t -> t.engine().equals("mustache"))
                .collect(Collectors.toList());

        this.file = nodes.stream()
                .flatMap(output -> output.children().stream())
                .filter(o -> o instanceof FileSetAST)
                .map(t -> (FileSetAST) t)
                .collect(Collectors.toList());

        this.files = nodes.stream()
                .flatMap(output -> output.children().stream())
                .filter(o -> o instanceof FileSetsAST)
                .map(t -> (FileSetsAST) t)
                .collect(Collectors.toList());
    }

    private Map<String, String> parseContextProperties(Map<String, ContextNodeAST> context) {
        if (context == null) {
            return new HashMap<>();
        }

        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, ContextNodeAST> entry : context.entrySet()) {
            ContextNodeAST node = entry.getValue();
            if (node instanceof ContextBooleanAST) {
                resolved.put(entry.getKey(), String.valueOf(((ContextBooleanAST) node).bool()));
            }
            if (node instanceof ContextTextAST) {
                resolved.put(entry.getKey(), ((ContextTextAST) node).text());
            }
        }
        return resolved;
    }

    /**
     * Generate output files.
     *
     * @param outputDirectory Output directory where the files will be generated
     */
    public void generate(File outputDirectory) throws IOException {
        Objects.requireNonNull(outputDirectory, "output directory is null");

        for (TemplateAST templateAST : template) {
            File outputFile = new File(outputDirectory, templateAST.target());
            outputFile.getParentFile().mkdirs();
            try (InputStream inputStream = archetype.getInputStream(templateAST.source())) {
                if (templateAST.engine().equals("mustache")) {
                    MustacheHandler.renderMustacheTemplate(
                            inputStream,
                            templateAST.source(),
                            new FileOutputStream(outputFile),
                            model);
                } else {
                    Files.copy(inputStream, outputFile.toPath());
                }
            }
        }

        for (TemplatesAST templatesAST : templates) {
            Path rootDirectory = Path.of(templatesAST.location().currentDirectory()).resolve(templatesAST.directory());
            TemplateModel templatesModel = createTemplatesModel(templatesAST);

            for (String include : resolveIncludes(templatesAST)) {
                String outPath = transform(
                        targetPath(templatesAST.directory(), include),
                        templatesAST.transformation());
                File outputFile = new File(outputDirectory, outPath);
                outputFile.getParentFile().mkdirs();
                try (InputStream inputStream = archetype.getInputStream(rootDirectory.resolve(include).toString())) {
                    MustacheHandler.renderMustacheTemplate(
                            inputStream,
                            outPath,
                            new FileOutputStream(outputFile),
                            templatesModel);
                }
            }
        }

        for (FileSetAST fileAST : file) {
            File outputFile = new File(outputDirectory, fileAST.target());
            outputFile.getParentFile().mkdirs();
            try (InputStream inputStream = archetype.getInputStream(fileAST.source())) {
                Files.copy(inputStream, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        for (FileSetsAST filesAST : files) {
            Path rootDirectory = Path.of(filesAST.location().currentDirectory()).resolve(filesAST.directory());
            for (String include : resolveIncludes(filesAST)) {
                String outPath = processTransformation(
                        targetPath(filesAST.directory(), include),
                        filesAST.transformations());
                File outputFile = new File(outputDirectory, outPath);
                outputFile.getParentFile().mkdirs();
                try (InputStream inputStream = archetype.getInputStream(rootDirectory.resolve(include).toString())) {
                    Files.copy(inputStream, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String targetPath(String directory, String filePath) {
        String resolved = directory.replaceFirst("files", "");
        return Path.of(resolved)
                .resolve(filePath)
                .toString();
    }

    private List<String> resolveIncludes(TemplatesAST templatesAST) {
        return resolveIncludes(
                Path.of(templatesAST.location().currentDirectory()).resolve(templatesAST.directory()).toString(),
                templatesAST.includes(),
                templatesAST.excludes());
    }

    private List<String> resolveIncludes(FileSetsAST filesAST) {
        return resolveIncludes(
                Path.of(filesAST.location().currentDirectory()).resolve(filesAST.directory()).toString(),
                filesAST.includes(),
                filesAST.excludes());
    }

    private List<String> resolveIncludes(String directory, List<String> includes, List<String> excludes) {
        List<String> excludesPath = getPathsFromDirectory(directory, excludes);
        List<String> includesPath = getPathsFromDirectory(directory, includes);
        return includesPath.stream()
                .filter(s -> !excludesPath.contains(s))
                .collect(Collectors.toList());
    }

    private List<String> getPathsFromDirectory(String directory, List<String> paths) {
        List<String> resolved = new LinkedList<>();

        for (String path : paths) {
            String finalDirectory = getDirectory(directory);
            resolved.addAll(archetype.getPaths().stream()
                    .filter(s -> PathFilters.matches(resolvePath(finalDirectory, path)).test(Path.of(s), Path.of("/")))
                    .map(s -> s.substring(finalDirectory.length() + 1))
                    .collect(Collectors.toList()));
        }
        return resolved;
    }

    private String resolvePath(String first, String second) {
        String resolved = first + "/" + second;
        return resolved
                .replaceAll("///", "/")
                .replaceAll("//", "/");
    }

    private String getDirectory(String directory) {
        return archetype instanceof ZipArchetype
                ? "/" + directory
                : directory;
    }

    private TemplateModel createTemplatesModel(TemplatesAST templatesAST) {
        TemplateModel templatesModel = new TemplateModel();
        Optional<ModelAST> modelAST = templatesAST.children().stream()
                .filter(o -> o instanceof ModelAST)
                .map(m -> (ModelAST) m)
                .findFirst();
        templatesModel.mergeModel(model.model());
        modelAST.ifPresent(templatesModel::mergeModel);
        return templatesModel;
    }

    private String transform(String input, String transformation) {
        return transformation == null ? input
                : processTransformation(input, Arrays.asList(transformation.split(",")));
    }

    private String processTransformation(String output, List<String> applicable) {
        if (applicable.isEmpty()) {
            return output;
        }

        List<Replacement> replacements = transformations.stream()
                .filter(t -> applicable.contains(t.id()))
                .flatMap((t) -> t.replacements().stream())
                .collect(Collectors.toList());

        for (Replacement rep : replacements) {
            String replacement = PropertyEvaluator.evaluate(rep.replacement(), properties);
            output = output.replaceAll(rep.regex(), replacement);
        }
        return output;
    }

    /**
     * Consume a list of output nodes from interpreter and create a unique template model.
     *
     * @return Unique template model
     */
    TemplateModel createUniqueModel() {
        Objects.requireNonNull(nodes, "outputNodes is null");

        TemplateModel templateModel = new TemplateModel();
        List<ModelAST> models = nodes.stream()
                .flatMap(output -> output.children().stream())
                .filter(o -> o instanceof ModelAST)
                .map(o -> (ModelAST) o)
                .collect(Collectors.toList());

        for (ModelAST node : models) {
            templateModel.mergeModel(node);
        }
        return templateModel;
    }

    private List<OutputAST> getOutputNodes(List<ASTNode> nodes) {
        return nodes.stream()
                .filter(o -> o instanceof OutputAST)
                .map(o -> (OutputAST) o)
                .collect(Collectors.toList());
    }

}