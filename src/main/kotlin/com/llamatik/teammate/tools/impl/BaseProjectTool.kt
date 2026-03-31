package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.tools.LlamatikTool

abstract class BaseProjectTool(protected val project: Project) : LlamatikTool {
    protected val contextService = ProjectContextService(project)
}
