package com.seibel.distanthorizons.lwjgl;

/**
 * Version-agnostic OpenGL debug message callback handler.
 */
@FunctionalInterface
public interface IDebugMessageHandler 
{
    void handle(int source, int type, int id, int severity, String message, EDebugExtension extension);
}
