package com.yuhan123.vulkanmod.mixin.gl;

import com.yuhan123.vulkanmod.gl.MatrixState;
import org.lwjgl.util.glu.Project;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Project.class)
public class ProjectMixin {
    @Overwrite(remap = false)
    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
        MatrixState.perspective(fovy, aspect, zNear, zFar);
    }
}
