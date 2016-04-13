/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class to create task view animations for {@link TaskView}s in a {@link TaskStackView},
 * but not the contents of the {@link TaskView}s.
 */
public class TaskStackAnimationHelper {

    /**
     * Callbacks from the helper to coordinate view-content animations with view animations.
     */
    public interface Callbacks {
        /**
         * Callback to prepare for the start animation for the launch target {@link TaskView}.
         */
        void onPrepareLaunchTargetForEnterAnimation();

        /**
         * Callback to start the animation for the launch target {@link TaskView}.
         */
        void onStartLaunchTargetEnterAnimation(TaskViewTransform transform, int duration,
                boolean screenPinningEnabled, ReferenceCountedTrigger postAnimationTrigger);

        /**
         * Callback to start the animation for the launch target {@link TaskView} when it is
         * launched from Recents.
         */
        void onStartLaunchTargetLaunchAnimation(int duration, boolean screenPinningRequested,
                ReferenceCountedTrigger postAnimationTrigger);

        /**
         * Callback to start the animation for the front {@link TaskView} if there is no launch
         * target.
         */
        void onStartFrontTaskEnterAnimation(boolean screenPinningEnabled);
    }

    private static final int FRAME_OFFSET_MS = 16;

    public static final int ENTER_FROM_HOME_ALPHA_DURATION = 100;
    public static final int ENTER_FROM_HOME_TRANSLATION_DURATION = 333;
    public static final int ENTER_WHILE_DOCKING_DURATION = 250;

    private static final PathInterpolator ENTER_FROM_HOME_TRANSLATION_INTERPOLATOR =
            new PathInterpolator(0, 0, 0, 1f);
    private static final PathInterpolator ENTER_FROM_HOME_ALPHA_INTERPOLATOR =
            new PathInterpolator(0, 0, 0.2f, 1f);

    public static final int EXIT_TO_HOME_ALPHA_DURATION = 100;
    public static final int EXIT_TO_HOME_TRANSLATION_DURATION = 150;
    private static final PathInterpolator EXIT_TO_HOME_TRANSLATION_INTERPOLATOR =
            new PathInterpolator(0.8f, 0, 0.6f, 1f);
    private static final PathInterpolator EXIT_TO_HOME_ALPHA_INTERPOLATOR =
            new PathInterpolator(0.4f, 0, 1f, 1f);

    private static final PathInterpolator FOCUS_NEXT_TASK_INTERPOLATOR =
            new PathInterpolator(0.4f, 0, 0, 1f);
    private static final PathInterpolator FOCUS_IN_FRONT_NEXT_TASK_INTERPOLATOR =
            new PathInterpolator(0, 0, 0, 1f);
    private static final PathInterpolator FOCUS_BEHIND_NEXT_TASK_INTERPOLATOR =
            new PathInterpolator(0.4f, 0, 0.2f, 1f);

    private static final PathInterpolator ENTER_WHILE_DOCKING_INTERPOLATOR =
            new PathInterpolator(0, 0, 0.2f, 1f);

    private TaskStackView mStackView;

    private TaskViewTransform mTmpTransform = new TaskViewTransform();
    private ArrayList<TaskViewTransform> mTmpCurrentTaskTransforms = new ArrayList<>();
    private ArrayList<TaskViewTransform> mTmpFinalTaskTransforms = new ArrayList<>();

    public TaskStackAnimationHelper(Context context, TaskStackView stackView) {
        mStackView = stackView;
    }

    /**
     * Prepares the stack views and puts them in their initial animation state while visible, before
     * the in-app enter animations start (after the window-transition completes).
     */
    public void prepareForEnterAnimation() {
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        Resources res = mStackView.getResources();

        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStackViewScroller stackScroller = mStackView.getScroller();
        TaskStack stack = mStackView.getStack();
        Task launchTargetTask = stack.getLaunchTarget();

        // Break early if there are no tasks
        if (stack.getTaskCount() == 0) {
            return;
        }

        int offscreenYOffset = stackLayout.mStackRect.height();
        int taskViewAffiliateGroupEnterOffset = res.getDimensionPixelSize(
                R.dimen.recents_task_stack_animation_affiliate_enter_offset);
        int launchedWhileDockingOffset = res.getDimensionPixelSize(
                R.dimen.recents_task_stack_animation_launched_while_docking_offset);
        boolean isLandscape = mStackView.getContext().getApplicationContext().getResources()
                .getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        // Prepare each of the task views for their enter animation from front to back
        List<TaskView> taskViews = mStackView.getTaskViews();
        for (int i = taskViews.size() - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            boolean currentTaskOccludesLaunchTarget = (launchTargetTask != null &&
                    launchTargetTask.group.isTaskAboveTask(task, launchTargetTask));
            boolean hideTask = (launchTargetTask != null &&
                    launchTargetTask.isFreeformTask() && task.isFreeformTask());

            // Get the current transform for the task, which will be used to position it offscreen
            stackLayout.getStackTransform(task, stackScroller.getStackScroll(), mTmpTransform,
                    null);

            if (hideTask) {
                tv.setVisibility(View.INVISIBLE);
            } else if (launchState.launchedFromApp && !launchState.launchedViaDockGesture) {
                if (task.isLaunchTarget) {
                    tv.onPrepareLaunchTargetForEnterAnimation();
                } else if (currentTaskOccludesLaunchTarget) {
                    // Move the task view slightly lower so we can animate it in
                    mTmpTransform.rect.offset(0, taskViewAffiliateGroupEnterOffset);
                    mTmpTransform.alpha = 0f;
                    mStackView.updateTaskViewToTransform(tv, mTmpTransform,
                            AnimationProps.IMMEDIATE);
                    tv.setClipViewInStack(false);
                }
            } else if (launchState.launchedFromHome) {
                // Move the task view off screen (below) so we can animate it in
                mTmpTransform.rect.offset(0, offscreenYOffset);
                mTmpTransform.alpha = 0f;
                mStackView.updateTaskViewToTransform(tv, mTmpTransform, AnimationProps.IMMEDIATE);
            } else if (launchState.launchedViaDockGesture) {
                int offset = isLandscape
                        ? launchedWhileDockingOffset
                        : (int) (offscreenYOffset * 0.9f);
                mTmpTransform.rect.offset(0, offset);
                mTmpTransform.alpha = 0f;
                mStackView.updateTaskViewToTransform(tv, mTmpTransform, AnimationProps.IMMEDIATE);
            }
        }
    }

    /**
     * Starts the in-app enter animation, which animates the {@link TaskView}s to their final places
     * depending on how Recents was triggered.
     */
    public void startEnterAnimation(final ReferenceCountedTrigger postAnimationTrigger) {
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        Resources res = mStackView.getResources();

        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStackViewScroller stackScroller = mStackView.getScroller();
        TaskStack stack = mStackView.getStack();
        Task launchTargetTask = stack.getLaunchTarget();

        // Break early if there are no tasks
        if (stack.getTaskCount() == 0) {
            return;
        }

        int taskViewEnterFromAppDuration = res.getInteger(
                R.integer.recents_task_enter_from_app_duration);
        int taskViewEnterFromAffiliatedAppDuration = res.getInteger(
                R.integer.recents_task_enter_from_affiliated_app_duration);

        // Create enter animations for each of the views from front to back
        List<TaskView> taskViews = mStackView.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            int taskIndexFromFront = taskViewCount - i - 1;
            int taskIndexFromBack = i;
            final TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            boolean currentTaskOccludesLaunchTarget = false;
            if (launchTargetTask != null) {
                currentTaskOccludesLaunchTarget = launchTargetTask.group.isTaskAboveTask(task,
                        launchTargetTask);
            }

            // Get the current transform for the task, which will be updated to the final transform
            // to animate to depending on how recents was invoked
            stackLayout.getStackTransform(task, stackScroller.getStackScroll(), mTmpTransform,
                    null);

            if (launchState.launchedFromApp && !launchState.launchedViaDockGesture) {
                if (task.isLaunchTarget) {
                    tv.onStartLaunchTargetEnterAnimation(mTmpTransform,
                            taskViewEnterFromAppDuration, mStackView.mScreenPinningEnabled,
                            postAnimationTrigger);
                } else {
                    // Animate the task up if it was occluding the launch target
                    if (currentTaskOccludesLaunchTarget) {
                        AnimationProps taskAnimation = new AnimationProps(
                                taskViewEnterFromAffiliatedAppDuration, Interpolators.ALPHA_IN,
                                new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        postAnimationTrigger.decrement();
                                        tv.setClipViewInStack(true);
                                    }
                                });
                        postAnimationTrigger.increment();
                        mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
                    }
                }

            } else if (launchState.launchedFromHome) {
                // Animate the tasks up
                AnimationProps taskAnimation = new AnimationProps()
                        .setStartDelay(AnimationProps.ALPHA, taskIndexFromFront * FRAME_OFFSET_MS)
                        .setDuration(AnimationProps.ALPHA, ENTER_FROM_HOME_ALPHA_DURATION)
                        .setDuration(AnimationProps.BOUNDS, ENTER_FROM_HOME_TRANSLATION_DURATION -
                                (taskIndexFromFront * FRAME_OFFSET_MS))
                        .setInterpolator(AnimationProps.BOUNDS,
                                ENTER_FROM_HOME_TRANSLATION_INTERPOLATOR)
                        .setInterpolator(AnimationProps.ALPHA,
                                ENTER_FROM_HOME_ALPHA_INTERPOLATOR)
                        .setListener(postAnimationTrigger.decrementOnAnimationEnd());
                postAnimationTrigger.increment();
                mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
                if (i == taskViewCount - 1) {
                    tv.onStartFrontTaskEnterAnimation(mStackView.mScreenPinningEnabled);
                }
            } else if (launchState.launchedViaDockGesture) {
                // Animate the tasks up
                AnimationProps taskAnimation = new AnimationProps()
                        .setDuration(AnimationProps.BOUNDS, (int) (ENTER_WHILE_DOCKING_DURATION +
                                                        (taskIndexFromBack * 2f * FRAME_OFFSET_MS)))
                        .setInterpolator(AnimationProps.BOUNDS,
                                ENTER_WHILE_DOCKING_INTERPOLATOR)
                        .setListener(postAnimationTrigger.decrementOnAnimationEnd());
                postAnimationTrigger.increment();
                mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
            }
        }
    }

    /**
     * Starts an in-app animation to hide all the task views so that we can transition back home.
     */
    public void startExitToHomeAnimation(boolean animated,
            ReferenceCountedTrigger postAnimationTrigger) {
        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStack stack = mStackView.getStack();

        // Break early if there are no tasks
        if (stack.getTaskCount() == 0) {
            return;
        }

        int offscreenYOffset = stackLayout.mStackRect.height();

        // Create the animations for each of the tasks
        List<TaskView> taskViews = mStackView.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            int taskIndexFromFront = taskViewCount - i - 1;
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();

            // Animate the tasks down
            AnimationProps taskAnimation;
            if (animated) {
                taskAnimation = new AnimationProps()
                        .setStartDelay(AnimationProps.ALPHA, i * FRAME_OFFSET_MS)
                        .setDuration(AnimationProps.ALPHA, EXIT_TO_HOME_ALPHA_DURATION)
                        .setDuration(AnimationProps.BOUNDS, EXIT_TO_HOME_TRANSLATION_DURATION +
                                (taskIndexFromFront * FRAME_OFFSET_MS))
                        .setInterpolator(AnimationProps.BOUNDS,
                                EXIT_TO_HOME_TRANSLATION_INTERPOLATOR)
                        .setInterpolator(AnimationProps.ALPHA,
                                EXIT_TO_HOME_ALPHA_INTERPOLATOR)
                        .setListener(postAnimationTrigger.decrementOnAnimationEnd());
                postAnimationTrigger.increment();
            } else {
                taskAnimation = AnimationProps.IMMEDIATE;
            }

            mTmpTransform.fillIn(tv);
            mTmpTransform.alpha = 0f;
            mTmpTransform.rect.offset(0, offscreenYOffset);
            mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
        }
    }

    /**
     * Starts the animation for the launching task view, hiding any tasks that might occlude the
     * window transition for the launching task.
     */
    public void startLaunchTaskAnimation(TaskView launchingTaskView, boolean screenPinningRequested,
            final ReferenceCountedTrigger postAnimationTrigger) {
        Resources res = mStackView.getResources();

        int taskViewExitToAppDuration = res.getInteger(
                R.integer.recents_task_exit_to_app_duration);
        int taskViewAffiliateGroupEnterOffset = res.getDimensionPixelSize(
                R.dimen.recents_task_stack_animation_affiliate_enter_offset);

        Task launchingTask = launchingTaskView.getTask();
        List<TaskView> taskViews = mStackView.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            boolean currentTaskOccludesLaunchTarget = (launchingTask != null &&
                    launchingTask.group.isTaskAboveTask(task, launchingTask));

            if (tv == launchingTaskView) {
                tv.setClipViewInStack(false);
                postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                    @Override
                    public void run() {
                        tv.setClipViewInStack(true);
                    }
                });
                tv.onStartLaunchTargetLaunchAnimation(taskViewExitToAppDuration,
                        screenPinningRequested, postAnimationTrigger);
            } else if (currentTaskOccludesLaunchTarget) {
                // Animate this task out of view
                AnimationProps taskAnimation = new AnimationProps(
                        taskViewExitToAppDuration, Interpolators.ALPHA_OUT,
                        postAnimationTrigger.decrementOnAnimationEnd());
                postAnimationTrigger.increment();

                mTmpTransform.fillIn(tv);
                mTmpTransform.alpha = 0f;
                mTmpTransform.rect.offset(0, taskViewAffiliateGroupEnterOffset);
                mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
            }
        }
    }

    /**
     * Starts the delete animation for the specified {@link TaskView}.
     */
    public void startDeleteTaskAnimation(final TaskView deleteTaskView,
            final ReferenceCountedTrigger postAnimationTrigger) {
        Resources res = mStackView.getResources();
        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();

        int taskViewRemoveAnimDuration = res.getInteger(
                R.integer.recents_animate_task_view_remove_duration);
        int offscreenXOffset = mStackView.getMeasuredWidth() - stackLayout.mTaskRect.left;

        // Disabling clipping with the stack while the view is animating away, this will get
        // restored when the task is next picked up from the view pool
        deleteTaskView.setClipViewInStack(false);

        // Compose the new animation and transform and star the animation
        AnimationProps taskAnimation = new AnimationProps(taskViewRemoveAnimDuration,
                Interpolators.ALPHA_OUT, new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                postAnimationTrigger.decrement();
            }
        });
        postAnimationTrigger.increment();

        mTmpTransform.fillIn(deleteTaskView);
        mTmpTransform.alpha = 0f;
        mTmpTransform.rect.offset(offscreenXOffset, 0);
        mStackView.updateTaskViewToTransform(deleteTaskView, mTmpTransform, taskAnimation);
    }

    /**
     * Starts the delete animation for all the {@link TaskView}s.
     */
    public void startDeleteAllTasksAnimation(final List<TaskView> taskViews,
                                             final ReferenceCountedTrigger postAnimationTrigger) {
        Resources res = mStackView.getResources();
        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();

        int taskViewRemoveAnimDuration = res.getInteger(
                R.integer.recents_animate_task_views_remove_all_duration);
        int offscreenXOffset = mStackView.getMeasuredWidth() - stackLayout.mTaskRect.left;

        int taskViewCount = taskViews.size();
        int startDelayMax = 125;

        for (int i = taskViewCount - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            int indexFromFront = taskViewCount - i - 1;
            float x = Interpolators.ACCELERATE.getInterpolation((float) indexFromFront /
                    taskViewCount);
            int startDelay = (int) Utilities.mapRange(x, 0, startDelayMax);

            // Disabling clipping with the stack while the view is animating away
            tv.setClipViewInStack(false);

            // Compose the new animation and transform and star the animation
            AnimationProps taskAnimation = new AnimationProps(startDelay,
                    taskViewRemoveAnimDuration, Interpolators.FAST_OUT_LINEAR_IN,
                    new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    postAnimationTrigger.decrement();

                    // Re-enable clipping with the stack (we will reuse this view)
                    tv.setClipViewInStack(true);
                }
            });
            postAnimationTrigger.increment();

            mTmpTransform.fillIn(tv);
            mTmpTransform.rect.offset(offscreenXOffset, 0);
            mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
        }
    }

    /**
     * Starts the animation to focus the next {@link TaskView} when paging through recents.
     *
     * @return whether or not this will trigger a scroll in the stack
     */
    public boolean startScrollToFocusedTaskAnimation(Task newFocusedTask,
            boolean requestViewFocus) {
        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStackViewScroller stackScroller = mStackView.getScroller();
        TaskStack stack = mStackView.getStack();

        final float curScroll = stackScroller.getStackScroll();
        final float newScroll = stackScroller.getBoundedStackScroll(
                stackLayout.getStackScrollForTask(newFocusedTask));
        boolean willScrollToFront = newScroll > curScroll;
        boolean willScroll = Float.compare(newScroll, curScroll) != 0;

        // Get the current set of task transforms
        int taskViewCount = mStackView.getTaskViews().size();
        ArrayList<Task> stackTasks = stack.getStackTasks();
        mStackView.getCurrentTaskTransforms(stackTasks, mTmpCurrentTaskTransforms);

        // Pick up the newly visible views after the scroll
        mStackView.bindVisibleTaskViews(newScroll);

        // Update the internal state
        stackLayout.setFocusState(TaskStackLayoutAlgorithm.STATE_FOCUSED);
        stackScroller.setStackScroll(newScroll, null /* animation */);
        mStackView.cancelDeferredTaskViewLayoutAnimation();

        // Get the final set of task transforms
        mStackView.getLayoutTaskTransforms(newScroll, stackLayout.getFocusState(), stackTasks,
                mTmpFinalTaskTransforms);

        // Focus the task view
        TaskView newFocusedTaskView = mStackView.getChildViewForTask(newFocusedTask);
        if (newFocusedTaskView == null) {
            // Log the error if we have no task view, and skip the animation
            Log.e("TaskStackAnimationHelper", "b/27389156 null-task-view prebind:" + taskViewCount +
                    " postbind:" + mStackView.getTaskViews().size() + " prescroll:" + curScroll +
                    " postscroll: " + newScroll);
            return false;
        }
        newFocusedTaskView.setFocusedState(true, requestViewFocus);

        // Setup the end listener to return all the hidden views to the view pool after the
        // focus animation
        ReferenceCountedTrigger postAnimTrigger = new ReferenceCountedTrigger();
        postAnimTrigger.addLastDecrementRunnable(new Runnable() {
            @Override
            public void run() {
                mStackView.bindVisibleTaskViews(newScroll);
            }
        });

        List<TaskView> taskViews = mStackView.getTaskViews();
        taskViewCount = taskViews.size();
        int newFocusTaskViewIndex = taskViews.indexOf(newFocusedTaskView);
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();

            if (mStackView.isIgnoredTask(task)) {
                continue;
            }

            int taskIndex = stackTasks.indexOf(task);
            TaskViewTransform fromTransform = mTmpCurrentTaskTransforms.get(taskIndex);
            TaskViewTransform toTransform = mTmpFinalTaskTransforms.get(taskIndex);

            // Update the task to the initial state (for the newly picked up tasks)
            mStackView.updateTaskViewToTransform(tv, fromTransform, AnimationProps.IMMEDIATE);

            int duration;
            Interpolator interpolator;
            if (willScrollToFront) {
                duration = Math.max(100, 100 + ((i - 1) * 50));
                interpolator = FOCUS_BEHIND_NEXT_TASK_INTERPOLATOR;
            } else {
                if (i < newFocusTaskViewIndex) {
                    duration = 150 + ((newFocusTaskViewIndex - i - 1) * 50);
                    interpolator = FOCUS_BEHIND_NEXT_TASK_INTERPOLATOR;
                } else if (i > newFocusTaskViewIndex) {
                    duration = Math.max(100, 150 - ((i - newFocusTaskViewIndex - 1) * 50));
                    interpolator = FOCUS_IN_FRONT_NEXT_TASK_INTERPOLATOR;
                } else {
                    duration = 200;
                    interpolator = FOCUS_NEXT_TASK_INTERPOLATOR;
                }
            }

            AnimationProps anim = new AnimationProps()
                    .setDuration(AnimationProps.BOUNDS, duration)
                    .setInterpolator(AnimationProps.BOUNDS, interpolator)
                    .setListener(postAnimTrigger.decrementOnAnimationEnd());
            postAnimTrigger.increment();
            mStackView.updateTaskViewToTransform(tv, toTransform, anim);
        }
        return willScroll;
    }
}
