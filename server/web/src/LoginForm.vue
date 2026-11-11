<script setup lang="ts">
import { ref } from 'vue';
defineProps<{ busy: boolean; error: string }>();const emit = defineEmits<{ login: [username: string, code: string] }>();
const username = ref('');const code = ref('');
function submit() { const secret = code.value;code.value = '';emit('login', username.value, secret); }
</script>
<template><main class="login"><p class="brand">应用管控</p><h1>远程管理</h1><p class="muted">登录后管理已接入的设备。</p><form @submit.prevent="submit"><label>管理员账号<input v-model="username" autocomplete="username" required maxlength="100"></label><label>动态验证码<input v-model="code" type="text" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" required minlength="6" maxlength="6"></label><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button></form></main></template>
