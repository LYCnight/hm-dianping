package com.hmdp.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// 这个代码由 lycnight 创建，用于学习线程池

public class ThreadPoolExample {

    public static void main(String[] args) {
        // 创建一个固定大小为10的线程池
        ExecutorService executor = Executors.newFixedThreadPool(10);


        // 提交10个任务给线程池执行
        for (int i = 1; i <= 10; i++) {
            int taskId = i;
            executor.submit(() -> {
                System.out.println("执行任务 " + taskId + " 由线程 " + Thread.currentThread().getName() + " 处理");
                // 模拟任务执行时间
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });
        }

        // 关闭线程池，不再接受新任务，已提交的任务继续执行
        executor.shutdown();

        // 等待所有任务完成，或者超时
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                // 超时后终止当前正在执行的任务
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            // 如果等待时被中断，重新设置中断状态
            Thread.currentThread().interrupt();
            // 尝试立即终止正在执行的任务
            executor.shutdownNow();
        }

        System.out.println("所有任务执行完毕");
    }
}