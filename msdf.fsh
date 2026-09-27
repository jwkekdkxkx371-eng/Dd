#version 150

uniform sampler2D Sampler0;
uniform vec4 Color;
uniform float PxRange;

in vec2 texCoord;
out vec4 fragColor;

float median(float red, float green, float blue) {
    return max(min(red, green), min(max(red, green), blue));
}

void main() {
    vec3 sampleValue = texture(Sampler0, texCoord).rgb;
    float signedDistance = median(sampleValue.r, sampleValue.g, sampleValue.b) - 0.5;
    vec2 unitRange = vec2(PxRange) / vec2(textureSize(Sampler0, 0));
    vec2 screenTextureSize = 1.0 / fwidth(texCoord);
    float screenRange = max(0.5 * dot(unitRange, screenTextureSize), 1.0);
    float opacity = clamp(signedDistance * screenRange + 0.5, 0.0, 1.0);
    fragColor = vec4(Color.rgb, Color.a * opacity);
}