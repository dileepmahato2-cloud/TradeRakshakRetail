import { Injectable } from '@angular/core';
import { ApiService } from './api.service';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class TeachingService {
  constructor(private api: ApiService) {}

  // Dashboard
  getDashboard(): Observable<any>         { return this.api.get('/teaching/dashboard'); }
  getMyLearning(): Observable<any>        { return this.api.get('/teaching/my-learning'); }

  // Courses
  getCourses(status?: string): Observable<any> {
    return this.api.get('/teaching/courses', status ? { status } : undefined);
  }
  getCourse(id: number): Observable<any>  { return this.api.get(`/teaching/courses/${id}`); }
  createCourse(data: any): Observable<any>{ return this.api.post('/teaching/courses', data); }
  updateCourse(id: number, data: any)     { return this.api.put(`/teaching/courses/${id}`, data); }
  publishCourse(id: number)               { return this.api.post(`/teaching/courses/${id}/publish`, {}); }
  archiveCourse(id: number)               { return this.api.delete(`/teaching/courses/${id}`); }

  // Topics & Lessons
  addTopic(courseId: number, data: any)   { return this.api.post(`/teaching/courses/${courseId}/topics`, data); }
  getTopics(courseId: number)             { return this.api.get(`/teaching/courses/${courseId}/topics`); }
  addLesson(topicId: number, data: any)   { return this.api.post(`/teaching/topics/${topicId}/lessons`, data); }
  getLessons(topicId: number)             { return this.api.get(`/teaching/topics/${topicId}/lessons`); }
  updateProgress(lessonId: number, progressPct: number, positionSecs: number) {
    return this.api.post(`/teaching/lessons/${lessonId}/progress`, { progressPct, positionSecs });
  }

  // Batches
  getBatches(): Observable<any>           { return this.api.get('/teaching/batches'); }
  getBatch(id: number): Observable<any>   { return this.api.get(`/teaching/batches/${id}`); }
  createBatch(data: any): Observable<any> { return this.api.post('/teaching/batches', data); }

  // Enrollments
  enroll(courseId: number, batchId?: number) {
    const params = batchId ? `?batchId=${batchId}` : '';
    return this.api.post(`/teaching/courses/${courseId}/enroll${params}`, {});
  }
  enrollUser(courseId: number, data: any) { return this.api.post(`/teaching/courses/${courseId}/enroll-user`, data); }
  bulkEnroll(courseId: number, data: any) { return this.api.post(`/teaching/courses/${courseId}/bulk-enroll`, data); }

  // Live Classes
  getLiveClasses(status?: string)         { return this.api.get('/teaching/live-classes', status ? { status } : undefined); }
  createLiveClass(data: any)              { return this.api.post('/teaching/live-classes', data); }
  startClass(id: number)                  { return this.api.post(`/teaching/live-classes/${id}/start`, {}); }
  endClass(id: number)                    { return this.api.post(`/teaching/live-classes/${id}/end`, {}); }
  markAttendance(id: number, data: any)   { return this.api.post(`/teaching/live-classes/${id}/attendance`, data); }
  getAttendance(id: number)               { return this.api.get(`/teaching/live-classes/${id}/attendance`); }

  // Quizzes
  getQuizzes(courseId: number)            { return this.api.get(`/teaching/courses/${courseId}/quizzes`); }
  createQuiz(courseId: number, data: any) { return this.api.post(`/teaching/courses/${courseId}/quizzes`, data); }
  getQuiz(id: number)                     { return this.api.get(`/teaching/quizzes/${id}`); }
  addQuestion(quizId: number, data: any)  { return this.api.post(`/teaching/quizzes/${quizId}/questions`, data); }
  submitQuiz(id: number, answers: any)    { return this.api.post(`/teaching/quizzes/${id}/submit`, answers); }

  // Assignments
  getAssignments(courseId: number)        { return this.api.get(`/teaching/courses/${courseId}/assignments`); }
  createAssignment(courseId: number, d: any) { return this.api.post(`/teaching/courses/${courseId}/assignments`, d); }
  submitAssignment(id: number, data: any) { return this.api.post(`/teaching/assignments/${id}/submit`, data); }
  getSubmissions(id: number)              { return this.api.get(`/teaching/assignments/${id}/submissions`); }
  gradeSubmission(id: number, data: any)  { return this.api.post(`/teaching/submissions/${id}/grade`, data); }

  // Certificates & Leaderboard
  getMyCertificates()                     { return this.api.get('/teaching/my-certificates'); }
  issueCertificate(enrollmentId: number)  { return this.api.post(`/teaching/enrollments/${enrollmentId}/issue-certificate`, {}); }
  getLeaderboard(params?: any)            { return this.api.get('/teaching/leaderboard', params); }

  // Forum & Announcements
  getForumThreads(courseId: number)       { return this.api.get(`/teaching/courses/${courseId}/forum`); }
  postThread(courseId: number, data: any) { return this.api.post(`/teaching/courses/${courseId}/forum`, data); }
  getAnnouncements(courseId?: number)     { return this.api.get('/teaching/announcements', courseId ? { courseId } : undefined); }
  createAnnouncement(data: any)           { return this.api.post('/teaching/announcements', data); }
  getResources(courseId?: number)         { return this.api.get('/teaching/resources', courseId ? { courseId } : undefined); }
}
