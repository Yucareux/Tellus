package com.seibel.distanthorizons.lwjgl;

/**
 * OpenGL extensions that can be queried via {@link ILWJGLService#isExtensionSupported(EGLExtension)}.
 */
public enum EGLExtension 
{
    ARB_buffer_storage,
    ARB_multi_draw_indirect,
    ARB_draw_elements_base_vertex,
    ARB_shader_storage_buffer_object,
    ARB_sync,
    ARB_timer_query,
    ARB_debug_output,
    KHR_debug,
    AMD_debug_output,
    ARB_uniform_buffer_object,
    ARB_vertex_array_object,
    ARB_map_buffer_range,
    ARB_copy_buffer,
    ARB_texture_storage,
    ARB_base_instance,
	ARB_instanced_arrays,
	ARB_compatibility
}
