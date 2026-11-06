<script setup lang="ts">
import { ref } from 'vue';
defineProps<{ busy: boolean; error: string }>();const emit = defineEmits<{ login: [username: string, password: string] }>();
const username = ref('');const password = ref('');
function submit() { const secret = password.value;password.value = '';emit('login', username.value, secret); }
</script>
<template><main class="login"><p class="brand">应用管控</p><h1>远程管理</h1><p class="muted">登录后管理已接入的设备。</p><form @submit.prevent="submit"><label>管理员账号<input v-model="username" autocomplete="username" required maxlength="100"></label><label>管理员密码<input v-model="password" type="password" autocomplete="current-password" required maxlength="256"></label><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button></form></main></template>
