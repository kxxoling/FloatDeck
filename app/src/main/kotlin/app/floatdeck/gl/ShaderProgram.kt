package app.floatdeck.gl

import android.opengl.GLES30

/** 着色器程序编译工具：编译顶点/片段着色器并链接成可用的 GL 程序。 */
object ShaderProgram {
    /**
     * 编译并链接着色器程序。
     * @return 链接成功的 GL 程序 ID
     * @throws RuntimeException 编译或链接失败时抛出
     */
    fun compile(
        vertexSource: String,
        fragmentSource: String,
    ): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)

        // 创建程序对象并附加两个着色器
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        // 检查链接状态
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Shader link failed: $log")
        }

        // 链接完成后即可删除着色器对象（程序已持有编译结果）
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        return program
    }

    /** 编译单个着色器（顶点或片段）。 */
    private fun compileShader(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }

        return shader
    }
}
